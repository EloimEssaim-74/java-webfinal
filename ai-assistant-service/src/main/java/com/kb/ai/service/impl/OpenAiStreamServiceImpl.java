package com.kb.ai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kb.ai.config.AiConfig;
import com.kb.ai.service.AiStreamService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容流式 API 实现.
 *
 * <h3>处理流程</h3>
 * <ol>
 *   <li>构建 Chat Completion 请求体（system + user messages, stream=true）</li>
 *   <li>通过 WebClient 调用 OpenAI 兼容 API</li>
 *   <li>逐行解析 SSE 响应 — 提取 {@code delta.content}</li>
 *   <li>包装为 {@link ServerSentEvent} 推送至客户端</li>
 *   <li>中途客户端断开 → {@code doOnCancel} 取消上游请求</li>
 * </ol>
 *
 * <h3>熔断策略</h3>
 * <ul>
 *   <li>滑动窗口 10 次调用，失败率 >= 50% → 熔断 30 秒</li>
 *   <li>半开状态允许 3 次探测调用</li>
 *   <li>熔断降级：返回单条 SSE "AI服务暂时不可用"</li>
 * </ul>
 *
 * <h3>Demo 模式</h3>
 * <p>当 {@code ai.demo-mode=true} 或未配置 API Key 时启用，
 * 返回模拟的逐字输出效果（每 200ms 推送 2-4 个字符），
 * 方便前端开发和演示.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAiStreamServiceImpl implements AiStreamService {

    private final AiConfig aiConfig;
    private final ObjectMapper objectMapper;
    private final ReactiveCircuitBreakerFactory<?, ?> circuitBreakerFactory;

    private WebClient webClient;
    private ReactiveCircuitBreaker circuitBreaker;

    private static final String SYSTEM_PROMPT =
            "你是一个专业的内容创作助手。请根据用户提供的上文内容，"
                    + "自然地续写后续段落。续写内容应：\n"
                    + "1. 保持与上文一致的语气和风格\n"
                    + "2. 逻辑连贯，内容有深度\n"
                    + "3. 使用中文书写（除非上文是其他语言）";

    private static final String[] DEMO_SENTENCES = {
            "让我来为你展开分析这个问题。",
            "从多个角度来看，这个主题有着丰富的内涵。",
            "首先，我们需要理解其核心概念和基本原理。",
            "深入探讨这个话题，我们会发现其中蕴含的深刻意义。",
            "这不仅是一个理论问题，更与实践紧密相连。",
            "综上所述，我们可以得出以下几点重要的认识。",
            "在实际应用中，这个思路已经被广泛验证。",
            "未来的发展方向值得我们持续关注和思考。"
    };

    @PostConstruct
    public void init() {
        // 初始化 WebClient（Reactor Netty 连接池）
        this.webClient = WebClient.builder()
                .baseUrl(aiConfig.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + aiConfig.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .codecs(config -> config.defaultCodecs().maxInMemorySize(512 * 1024))
                .build();

        // 初始化熔断器
        this.circuitBreaker = circuitBreakerFactory.create("openai-api");

        log.info("AI 流式服务初始化: baseUrl={}, model={}, demoMode={}",
                aiConfig.getBaseUrl(), aiConfig.getModel(), aiConfig.isDemoMode());
    }

    @Override
    public Flux<ServerSentEvent<String>> continueStream(String context, Long userId) {
        // Demo 模式或未配置 API Key → 模拟流式输出
        if (aiConfig.isDemoMode() || aiConfig.getApiKey() == null || aiConfig.getApiKey().isBlank()) {
            log.info("使用 Demo 模式: userId={}", userId);
            return demoStream();
        }

        // 真实模式 → 调用 OpenAI API（带熔断）
        return circuitBreaker.run(
                callOpenAiStream(context),
                throwable -> {
                    log.warn("熔断/异常降级: {}", throwable.getMessage());
                    return fallbackStream(throwable);
                }
        );
    }

    // ==================== 核心：OpenAI 流式调用 ====================

    private Flux<ServerSentEvent<String>> callOpenAiStream(String context) {
        Map<String, Object> requestBody = buildRequestBody(context);

        return webClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(requestBody)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)                                          // 逐行读取 SSE
                .filter(line -> line.startsWith("data:"))                          // 过滤非数据行
                .map(line -> line.substring(5).trim())                            // 去掉 "data:" 前缀
                .filter(data -> !data.isBlank() && !"[DONE]".equals(data))        // 过滤结束标记
                .map(this::extractContent)                                         // JSON → 文本
                .filter(token -> !token.isEmpty())                                 // 过滤空 token
                .map(token -> ServerSentEvent.<String>builder().data(token).build())
                .mergeWith(heartbeat())                                            // 合并心跳
                .timeout(Duration.ofSeconds(aiConfig.getTimeoutSeconds()))         // 超时保护
                .doOnCancel(() -> log.debug("上游 LLM 请求取消"));
    }

    /**
     * 构建 OpenAI Chat Completion 请求体.
     */
    private Map<String, Object> buildRequestBody(String context) {
        return Map.of(
                "model", aiConfig.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", context)
                ),
                "stream", true,
                "max_tokens", aiConfig.getMaxTokens(),
                "temperature", aiConfig.getTemperature()
        );
    }

    /**
     * 从 SSE data 行中提取 delta.content 文本.
     *
     * <p>OpenAI 流式响应格式示例:</p>
     * <pre>
     * {"choices":[{"delta":{"content":"这是"},"index":0}]}
     * </pre>
     */
    private String extractContent(String data) {
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode delta = choices.get(0).path("delta");
                JsonNode content = delta.path("content");
                if (!content.isMissingNode()) {
                    return content.asText();
                }
            }
        } catch (JsonProcessingException e) {
            log.debug("解析 SSE 数据失败: {}", data.substring(0, Math.min(100, data.length())));
        }
        return "";
    }

    // ==================== 心跳 ====================

    /**
     * 每 15 秒发送一次 SSE 注释作为心跳，防止网关/代理超时断开.
     *
     * <p>SSE 注释以冒号开头，浏览器 EventSource 会忽略，
     * 但 TCP 连接保持活跃.</p>
     */
    private Flux<ServerSentEvent<String>> heartbeat() {
        return Flux.interval(Duration.ofSeconds(15))
                .map(i -> ServerSentEvent.<String>builder()
                        .comment("keepalive")
                        .build());
    }

    // ==================== 降级 ====================

    /**
     * 熔断降级流 — 返回单条提示信息.
     */
    private Flux<ServerSentEvent<String>> fallbackStream(Throwable throwable) {
        String msg;
        if (throwable != null && throwable.getMessage() != null
                && throwable.getMessage().contains("timeout")) {
            msg = "AI 服务响应超时，请稍后重试。";
        } else {
            msg = "AI 服务暂时不可用，请稍后再试。";
        }
        return Flux.just(ServerSentEvent.<String>builder()
                .data(msg)
                .build());
    }

    // ==================== Demo 模式 ====================

    /**
     * 模拟流式输出 — 每 200ms 推送 2-4 个字符.
     *
     * <p>用于无 API Key 时的开发调试和演示.</p>
     */
    private Flux<ServerSentEvent<String>> demoStream() {
        StringBuilder fullText = new StringBuilder();
        for (String s : DEMO_SENTENCES) {
            fullText.append(s);
        }
        String text = fullText.toString();

        return Flux.fromStream(
                        // 将文本切分为 2-4 字符的片段
                        chunkText(text, 2, 4).stream()
                )
                .delayElements(Duration.ofMillis(200))              // 模拟打字效果
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build())
                .concatWith(Mono.just(                               // 末尾发送结束事件
                        ServerSentEvent.<String>builder()
                                .data("[DONE]")
                                .build()));
    }

    /**
     * 将文本切分为指定长度范围的片段列表.
     */
    private List<String> chunkText(String text, int minLen, int maxLen) {
        List<String> chunks = new java.util.ArrayList<>();
        int pos = 0;
        java.util.Random random = new java.util.Random(42);  // 固定种子保证可复现
        while (pos < text.length()) {
            int len = Math.min(minLen + random.nextInt(maxLen - minLen + 1),
                    text.length() - pos);
            if (len == 0) len = 1;
            chunks.add(text.substring(pos, pos + len));
            pos += len;
        }
        return chunks;
    }
}

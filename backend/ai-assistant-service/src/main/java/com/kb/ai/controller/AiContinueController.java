package com.kb.ai.controller;

import com.kb.ai.dto.ContinueRequest;
import com.kb.ai.service.AiStreamService;
import com.kb.common.constant.JwtConstants;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * AI 流式续写控制器.
 *
 * <h3>SSE 响应格式</h3>
 * <pre>
 * Content-Type: text/event-stream
 *
 * data: 这是
 *
 * data: 一段
 *
 * data: AI续写
 *
 * data: [DONE]
 * </pre>
 *
 * <h3>超时说明</h3>
 * <p>SSE 为长连接，无明确的 HTTP 超时。网关需配置
 * {@code response-timeout: 300000}（5分钟）.</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AiContinueController {

    private final AiStreamService aiStreamService;

    /**
     * AI 流式续写接口.
     *
     * <p>返回 {@code text/event-stream} 格式的 SSE 流，
     * 每个 {@code data:} 块包含 AI 生成的下一段文本.</p>
     *
     * @param request 包含上文的请求体
     * @param userId  从网关 X-User-Id 头获取（0 表示未登录但可体验 demo）
     * @return SSE 流
     */
    @PostMapping(value = "/api/ai/continue", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> continueStream(
            @Valid @RequestBody ContinueRequest request,
            @RequestHeader(value = JwtConstants.HEADER_USER_ID, defaultValue = "0") Long userId) {

        log.info("AI 续写请求: userId={}, contextLength={}", userId, request.getContext().length());

        return aiStreamService.continueStream(request.getContext(), userId)
                .doOnComplete(() -> log.info("AI 续写完成: userId={}", userId))
                .doOnCancel(() -> log.info("客户端断开连接: userId={}", userId))
                .doOnError(e -> log.error("AI 续写异常: userId={}, error={}", userId, e.getMessage()));
    }
}

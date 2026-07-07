package com.kb.tag.consumer;

import com.kb.common.entity.Article;
import com.kb.tag.mapper.ArticleMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 标签提取消费者 — 消费文章发布消息，模拟 AI 标签提取.
 *
 * <h3>处理流程</h3>
 * <ol>
 *   <li>从 RabbitMQ 消费 {@code article.tag.queue} 队列消息</li>
 *   <li>解析消息中的 title 和 content</li>
 *   <li>基于规则提取关键词作为标签（生产环境替换为 AI 调用）</li>
 *   <li>回写 MySQL {@code articles.tags} 字段</li>
 *   <li>手动 ACK 确认消费</li>
 * </ol>
 *
 * <h3>并发配置</h3>
 * <p>{@code concurrency = "5"} 表示 5 个并发消费者线程，
 * 标签提取是 CPU 密集型（文本处理），并发不宜过高.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TagExtractConsumer {

    private final ArticleMapper articleMapper;

    /**
     * 中文停用词表（简化版）.
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一",
            "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着",
            "没有", "看", "好", "自己", "这", "他", "她", "它", "们", "那", "些",
            "什么", "怎么", "如何", "为什么", "因为", "所以", "但是", "然而",
            "可以", "这个", "那个", "还是", "只是", "已经", "之后", "然后"
    );

    /**
     * 技术/科技领域特征词（用于内容分类）.
     */
    private static final Map<String, String> DOMAIN_PATTERNS = Map.ofEntries(
            Map.entry("java", "Java"),
            Map.entry("spring", "Spring"),
            Map.entry("python", "Python"),
            Map.entry("docker", "Docker"),
            Map.entry("redis", "Redis"),
            Map.entry("mysql", "MySQL"),
            Map.entry("微服务", "微服务"),
            Map.entry("分布式", "分布式"),
            Map.entry("AI", "人工智能"),
            Map.entry("机器学习", "机器学习"),
            Map.entry("前端", "前端开发"),
            Map.entry("后端", "后端开发"),
            Map.entry("数据库", "数据库"),
            Map.entry("架构", "系统架构"),
            Map.entry("算法", "算法"),
            Map.entry("安全", "安全")
    );

    @RabbitListener(
            queues = "article.tag.queue",
            concurrency = "5",
            ackMode = "MANUAL"
    )
    public void onMessage(Map<String, Object> message,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        Long articleId = toLong(message.get("articleId"));
        String title = Objects.toString(message.get("title"), "");
        String content = Objects.toString(message.get("content"), "");

        log.info("收到标签提取任务: articleId={}, title={}", articleId,
                title.length() > 30 ? title.substring(0, 30) + "..." : title);

        try {
            // 1. 提取标签
            String tags = extractTags(title, content);

            // 2. 回写数据库
            Article article = articleMapper.selectById(articleId);
            if (article != null) {
                article.setTags(tags);
                articleMapper.updateById(article);
                log.info("标签提取完成: articleId={}, tags={}", articleId, tags);
            } else {
                log.warn("文章不存在，跳过标签回写: articleId={}", articleId);
            }

            // 3. 手动 ACK
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            log.error("标签提取失败: articleId={}, error={}", articleId, e.getMessage());
            try {
                // 消费失败不重新入队（避免死循环），记录错误后 ACK
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException ioException) {
                log.error("Nack 失败: {}", ioException.getMessage());
            }
        }
    }

    /**
     * 标签提取核心逻辑（模拟 AI）.
     *
     * <p>生产环境应替换为调用 NLP 服务或 LLM API.
     * 当前实现：</p>
     * <ol>
     *   <li>从标题提取关键词（分词 + 去停用词）</li>
     *   <li>从内容匹配领域特征词</li>
     *   <li>合并去重，取前 5 个标签</li>
     * </ol>
     */
    private String extractTags(String title, String content) {
        Set<String> tags = new LinkedHashSet<>();

        // 1. 标题分词提取（按常见分隔符切分）
        String[] titleWords = title.split("[，,。\\s、：:；;！!？?\\-—()（）【】\\[\\]《》/\\\\|]+");
        for (String word : titleWords) {
            String trimmed = word.trim();
            if (trimmed.length() >= 2 && !STOP_WORDS.contains(trimmed)) {
                tags.add(trimmed);
            }
        }

        // 2. 内容领域匹配
        String lowerContent = content.toLowerCase();
        for (Map.Entry<String, String> entry : DOMAIN_PATTERNS.entrySet()) {
            if (lowerContent.contains(entry.getKey().toLowerCase())) {
                tags.add(entry.getValue());
            }
        }

        // 3. 去重、截取前 5 个
        return tags.stream()
                .limit(5)
                .collect(Collectors.joining(","));
    }

    private Long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}

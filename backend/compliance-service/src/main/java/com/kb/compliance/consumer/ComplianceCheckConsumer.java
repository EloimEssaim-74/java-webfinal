package com.kb.compliance.consumer;

import com.kb.common.entity.Article;
import com.kb.compliance.mapper.ArticleMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 合规检测消费者 — 消费文章发布消息，模拟 AI 内容审核.
 *
 * <h3>审核结果</h3>
 * <ul>
 *   <li>{@code PASS}   — 内容合规，正常展示</li>
 *   <li>{@code REVIEW} — 疑似违规，需人工复核</li>
 *   <li>{@code BLOCK}  — 确认违规，自动逻辑删除</li>
 * </ul>
 *
 * <h3>并发配置</h3>
 * <p>{@code concurrency = "5-10"} 表示最小 5、最大 10 个并发消费者.
 * 合规检测涉及外部 API 调用（模拟），IO 密集型，需要更多并发线程.</p>
 *
 * <h3>安全性说明</h3>
 * <p>当前敏感词表为演示用简化版本。生产环境应接入专业内容审核服务
 * （如阿里云内容安全、网易易盾），支持图片、视频等多模态检测.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComplianceCheckConsumer {

    private final ArticleMapper articleMapper;

    /**
     * 敏感词库（演示用简化版）.
     *
     * <p>生产环境应使用外部配置或专业审核服务.
     * 关键词不区分大小写.</p>
     */
    private static final Set<String> SENSITIVE_WORDS = Set.of(
            "违规", "违法", "赌博", "色情", "暴力",
            "诈骗", "传销", "毒品", "枪支", "走私"
    );

    /**
     * 触发人工复核的可疑词.
     */
    private static final Set<String> SUSPICIOUS_WORDS = Set.of(
            "广告", "推广", "联系方式", "加微信", "QQ群",
            "免费领取", "点击链接", "代理", "返利"
    );

    @RabbitListener(
            queues = "article.compliance.queue",
            concurrency = "5-10",
            ackMode = "MANUAL"
    )
    public void onMessage(Map<String, Object> message,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        Long articleId = toLong(message.get("articleId"));
        String title = Objects.toString(message.get("title"), "");
        String content = Objects.toString(message.get("content"), "");

        log.info("收到合规检测任务: articleId={}", articleId);

        try {
            // 1. 执行合规检测
            AuditResult result = audit(title, content);

            // 2. 回写/处理结果
            Article article = articleMapper.selectById(articleId);
            if (article == null) {
                log.warn("文章不存在，跳过合规回写: articleId={}", articleId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            switch (result) {
                case PASS -> {
                    article.setAuditResult("PASS");
                    articleMapper.updateById(article);
                    log.info("合规检测通过: articleId={}", articleId);
                }
                case REVIEW -> {
                    article.setAuditResult("REVIEW");
                    articleMapper.updateById(article);
                    log.warn("合规检测待复核: articleId={}, 疑似原因={}", articleId, result.reason());
                }
                case BLOCK -> {
                    // 确认违规：标记审核结果 + 逻辑删除
                    article.setAuditResult("BLOCK");
                    article.setDeleted(1);
                    articleMapper.updateById(article);
                    log.warn("合规检测违规拦截: articleId={}, 违规原因={}", articleId, result.reason());
                }
            }

            // 3. 手动 ACK
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            log.error("合规检测异常: articleId={}, error={}", articleId, e.getMessage());
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException ioException) {
                log.error("Nack 失败: {}", ioException.getMessage());
            }
        }
    }

    /**
     * 内容审核核心逻辑（模拟 AI）.
     *
     * <p>生产环境应替换为调用专业内容审核 API.
     * 当前实现基于敏感词匹配：</p>
     * <ul>
     *   <li>命中敏感词 → BLOCK</li>
     *   <li>命中可疑词 → REVIEW</li>
     *   <li>内容为空或过短 → REVIEW</li>
     *   <li>其他 → PASS</li>
     * </ul>
     */
    private AuditResult audit(String title, String content) {
        String fullText = (title + " " + content).toLowerCase();

        // 1. 内容过短 → 人工复核
        if (content == null || content.trim().length() < 10) {
            return AuditResult.REVIEW.withReason("内容过短，需人工判断");
        }

        // 2. 敏感词检测
        for (String word : SENSITIVE_WORDS) {
            if (fullText.contains(word)) {
                return AuditResult.BLOCK.withReason("包含敏感词: " + word);
            }
        }

        // 3. 可疑词检测
        for (String word : SUSPICIOUS_WORDS) {
            if (fullText.contains(word.toLowerCase())) {
                return AuditResult.REVIEW.withReason("包含可疑词: " + word);
            }
        }

        // 4. 通过
        return AuditResult.PASS;
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

    /**
     * 审核结果枚举（含原因描述）.
     */
    private enum AuditResult {
        PASS,
        REVIEW,
        BLOCK;

        private String reason;

        AuditResult withReason(String reason) {
            this.reason = reason;
            return this;
        }

        String reason() {
            return reason != null ? reason : "";
        }
    }
}

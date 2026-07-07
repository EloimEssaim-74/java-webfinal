package com.kb.interact.service.impl;

import com.kb.common.constant.RedisKeyConstants;
import com.kb.common.exception.BusinessException;
import com.kb.interact.service.LikeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LikeServiceImpl implements LikeService {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void likeArticle(Long articleId, Long userId) {
        // 1. Dedup check — one like per user per article (permanent)
        String dedupKey = String.format(RedisKeyConstants.LIKE_DEDUP, articleId, userId);
        try {
            Boolean success = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1");
            if (Boolean.FALSE.equals(success)) {
                throw new BusinessException("您已点赞过该文章");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("点赞去重检查失败: articleId={}, userId={}", articleId, userId, e);
            throw new BusinessException(500, "点赞服务暂不可用，请稍后重试");
        }

        // 2. Write to Redis Stream for async persistence
        try {
            Map<String, String> event = new HashMap<>();
            event.put("articleId", String.valueOf(articleId));
            event.put("userId", String.valueOf(userId));
            event.put("timestamp", String.valueOf(System.currentTimeMillis()));
            redisTemplate.opsForStream().add(RedisKeyConstants.LIKE_EVENTS_STREAM, event);
        } catch (Exception e) {
            log.error("点赞事件写入失败: articleId={}, userId={}", articleId, userId, e);
            // 回滚去重 key
            redisTemplate.delete(dedupKey);
            throw new BusinessException(500, "点赞服务暂不可用，请稍后重试");
        }

        // 3. Update hot articles ZSET (+3 for a like)
        try {
            redisTemplate.opsForZSet().incrementScore(
                    RedisKeyConstants.HOT_ARTICLES,
                    String.valueOf(articleId),
                    3);
        } catch (Exception e) {
            log.error("热搜更新失败: articleId={}", articleId, e);
            // 热度更新失败不影响主流程
        }

        // 4. Publish cache refresh notification to all article-service instances
        publishRefresh(articleId);

        log.debug("Like recorded: articleId={}, userId={}, heat +3", articleId, userId);
    }

    /**
     * 通过 Redis Pub/Sub 通知所有 article-service 实例刷新本地缓存.
     */
    private void publishRefresh(Long articleId) {
        try {
            redisTemplate.convertAndSend(
                    RedisKeyConstants.HOT_ARTICLES_REFRESH_CHANNEL,
                    "like:" + articleId);
        } catch (Exception e) {
            log.warn("Failed to publish hot articles refresh: {}", e.getMessage());
        }
    }
}

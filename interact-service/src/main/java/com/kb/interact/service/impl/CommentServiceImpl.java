package com.kb.interact.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kb.common.constant.RedisKeyConstants;
import com.kb.interact.service.CommentService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    @SneakyThrows
    public void createComment(Long articleId, Long userId, String content) {
        // 1. 将评论事件写入 Redis List，由消费者异步批量入库
        Map<String, Object> event = new HashMap<>();
        event.put("articleId", articleId);
        event.put("userId", userId);
        event.put("content", content);
        event.put("createdAt", LocalDateTime.now().toString());

        String json = objectMapper.writeValueAsString(event);
        redisTemplate.opsForList().leftPush(RedisKeyConstants.COMMENT_EVENTS, json);

        // 2. 更新热搜榜单 — 评论热度 +1
        redisTemplate.opsForZSet().incrementScore(
                RedisKeyConstants.HOT_ARTICLES,
                String.valueOf(articleId),
                1);

        // 3. 发布缓存刷新通知
        publishRefresh(articleId);

        log.debug("Comment event pushed: articleId={}, userId={}, heat +1", articleId, userId);
    }

    /**
     * 通过 Redis Pub/Sub 通知所有 article-service 实例刷新本地缓存.
     */
    private void publishRefresh(Long articleId) {
        try {
            redisTemplate.convertAndSend(
                    RedisKeyConstants.HOT_ARTICLES_REFRESH_CHANNEL,
                    "comment:" + articleId);
        } catch (Exception e) {
            log.warn("Failed to publish hot articles refresh: {}", e.getMessage());
        }
    }
}

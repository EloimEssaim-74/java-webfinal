package com.kb.article.listener;

import com.kb.article.cache.HotArticlesCacheManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Redis Pub/Sub 消息监听器 — 接收热搜刷新通知.
 *
 * <p>当任何服务实例修改了 hot_articles ZSET 后，
 * 通过 Redis Pub/Sub 广播刷新消息，所有实例收到后
 * 立即失效本地 Caffeine 缓存，保证缓存一致性。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotArticlesRefreshListener implements MessageListener {

    private final HotArticlesCacheManager cacheManager;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        log.debug("Received refresh message on channel '{}': {}", channel, body);
        cacheManager.invalidate();
    }
}

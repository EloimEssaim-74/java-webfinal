package com.kb.article.config;

import com.kb.article.listener.HotArticlesRefreshListener;
import com.kb.common.constant.RedisKeyConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub 消息监听配置.
 *
 * <p>注册 {@link HotArticlesRefreshListener} 到
 * {@code hot_articles:refresh} 频道，实现跨实例缓存失效.</p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisPubSubConfig {

    private final HotArticlesRefreshListener refreshListener;

    /**
     * Redis 消息监听容器 — 管理订阅生命周期.
     *
     * <p>容器在独立线程中运行，不阻塞主业务线程.
     * 直接注册实现了 {@link org.springframework.data.redis.connection.MessageListener} 的监听器.</p>
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(refreshListener,
                new ChannelTopic(RedisKeyConstants.HOT_ARTICLES_REFRESH_CHANNEL));
        log.info("Redis Pub/Sub listener registered on channel '{}'",
                RedisKeyConstants.HOT_ARTICLES_REFRESH_CHANNEL);
        return container;
    }
}

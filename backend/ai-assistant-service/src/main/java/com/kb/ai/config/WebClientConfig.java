package com.kb.ai.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * WebClient 全局配置 — Reactor Netty 连接池.
 *
 * <h3>连接池参数</h3>
 * <ul>
 *   <li>最大连接数: 100（限制对 LLM 后端的并发连接）</li>
 *   <li>pending 获取超时: 10 秒</li>
 *   <li>连接建立超时: 5 秒</li>
 *   <li>空闲超时: 60 秒</li>
 * </ul>
 *
 * <p>注意：WebClient 实例在 {@link com.kb.ai.service.impl.OpenAiStreamServiceImpl}
 * 中手动构建，此配置提供默认的 {@link HttpClient} 参数供其他场景使用.</p>
 */
@Configuration
public class WebClientConfig {

    @Bean
    public ConnectionProvider connectionProvider() {
        return ConnectionProvider.builder("ai-assistant-pool")
                .maxConnections(100)
                .pendingAcquireTimeout(Duration.ofSeconds(10))
                .maxIdleTime(Duration.ofSeconds(60))
                .build();
    }

    @Bean
    public HttpClient httpClient(ConnectionProvider connectionProvider) {
        return HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(120, TimeUnit.SECONDS))
                                .addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS)));
    }
}

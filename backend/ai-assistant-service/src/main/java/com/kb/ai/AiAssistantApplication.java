package com.kb.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * AI 流式创作辅助服务.
 *
 * <p>基于 Spring WebFlux 的响应式服务，提供 SSE 流式续写接口.
 * 不依赖 MySQL/MyBatis/Redis — 纯计算型服务.</p>
 */
@SpringBootApplication(
        scanBasePackages = "com.kb",
        exclude = {DataSourceAutoConfiguration.class}
)
@EnableDiscoveryClient
public class AiAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiAssistantApplication.class, args);
    }
}

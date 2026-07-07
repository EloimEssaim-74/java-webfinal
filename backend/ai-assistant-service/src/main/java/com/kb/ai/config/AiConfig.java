package com.kb.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 服务配置 — 绑定 application.yml 中 {@code ai.openai.*} 属性.
 *
 * <h3>配置来源优先级</h3>
 * <ol>
 *   <li>环境变量（Docker Compose 注入）</li>
 *   <li>application.yml 默认值</li>
 * </ol>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.openai")
public class AiConfig {

    /** OpenAI 兼容 API 地址 */
    private String baseUrl = "https://api.openai.com";

    /** API 密钥（生产环境通过环境变量 ${OPENAI_API_KEY} 注入） */
    private String apiKey = "";

    /** 模型名称 */
    private String model = "gpt-3.5-turbo";

    /** 最大生成 token 数 */
    private int maxTokens = 2048;

    /** 生成温度 (0-2)，越高越随机 */
    private double temperature = 0.7;

    /** 流式响应总超时（秒） */
    private int timeoutSeconds = 120;

    /** Demo 模式开关 — true 时使用本地模拟输出，不调用 LLM */
    private boolean demoMode = false;
}

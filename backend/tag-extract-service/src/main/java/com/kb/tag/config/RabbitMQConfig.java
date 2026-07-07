package com.kb.tag.config;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 消息转换器配置.
 *
 * <p>生产者 (article-service) 使用 Jackson2JsonMessageConverter 序列化消息,
 * 消费者必须使用相同的转换器才能正确反序列化为 Map&lt;String, Object&gt;.
 * 缺少此配置时, 消息被当作 byte[] 处理, 导致标签提取静默失败.</p>
 */
@Configuration
public class RabbitMQConfig {

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}

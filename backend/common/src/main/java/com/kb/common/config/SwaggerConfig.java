package com.kb.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc OpenAPI (Swagger UI) 配置.
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI knowledgePlatformOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Knowledge Platform API")
                        .description("基于 AI 的智能知识库与内容发布平台 — REST API 文档")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("开发团队")
                                .email("dev@knowledge-platform.com"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")));
    }
}

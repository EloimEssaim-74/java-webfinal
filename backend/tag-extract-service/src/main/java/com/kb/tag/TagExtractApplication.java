package com.kb.tag;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = "com.kb")
@EnableDiscoveryClient
@MapperScan("com.kb.tag.mapper")
public class TagExtractApplication {

    public static void main(String[] args) {
        SpringApplication.run(TagExtractApplication.class, args);
    }
}

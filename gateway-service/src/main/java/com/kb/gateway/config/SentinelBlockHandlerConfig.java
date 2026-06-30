package com.kb.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.kb.common.result.Result;
import com.kb.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Sentinel custom block handler — returns structured JSON (429) instead of
 * the default plain-text "Blocked by Sentinel" response.
 * <p>
 * The {@link BlockRequestHandler} bean is automatically picked up by
 * {@link com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter}
 * and invoked when any {@link BlockException} is thrown during route evaluation.
 */
@Slf4j
@Configuration
public class SentinelBlockHandlerConfig {

    @Bean
    public BlockRequestHandler blockRequestHandler() {
        return new BlockRequestHandler() {
            @Override
            public Mono<ServerResponse> handleRequest(ServerWebExchange exchange, Throwable ex) {
                // Log blocked request details for observability
                if (ex instanceof BlockException blockEx) {
                    log.debug("Sentinel blocked: path={}, resource={}, rule={}",
                            exchange.getRequest().getURI().getPath(),
                            blockEx.getRule().getResource(),
                            blockEx.getRuleLimitApp());
                } else {
                    log.debug("Sentinel blocked: path={}, cause={}",
                            exchange.getRequest().getURI().getPath(),
                            ex.getClass().getSimpleName());
                }

                return ServerResponse
                        .status(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Result.error(ResultCode.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试"));
            }
        };
    }
}

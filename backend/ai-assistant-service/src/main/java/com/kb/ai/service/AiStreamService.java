package com.kb.ai.service;

import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * AI 流式服务接口.
 */
public interface AiStreamService {

    /**
     * 流式续写 — 基于上文生成后续文本.
     *
     * @param context 上文内容
     * @param userId  用户 ID（用于限流/统计）
     * @return SSE 事件流
     */
    Flux<ServerSentEvent<String>> continueStream(String context, Long userId);
}

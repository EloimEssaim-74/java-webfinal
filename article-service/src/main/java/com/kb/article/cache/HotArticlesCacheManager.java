package com.kb.article.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kb.common.vo.TopArticleVO;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * 热搜榜单 Caffeine 本地缓存管理器.
 *
 * <p>缓存 Top 10 查询结果，TTL 可配置（默认 1 秒）。
 * 通过 Redis Pub/Sub 接收热度变更事件，主动失效缓存，
 * 保证多实例部署时的数据一致性。</p>
 */
@Slf4j
@Component
public class HotArticlesCacheManager {

    private final int cacheTtlSeconds;
    private Cache<String, List<TopArticleVO>> cache;

    public HotArticlesCacheManager(
            @Value("${hot-articles.cache-ttl-seconds:1}") int cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    @PostConstruct
    public void init() {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(cacheTtlSeconds))
                .maximumSize(1)
                .recordStats()
                .build();
        log.info("Hot articles Caffeine cache initialized: TTL={}s, maxSize=1", cacheTtlSeconds);
    }

    /**
     * 从缓存获取 Top 10 列表，缓存未命中时通过 supplier 计算并回填.
     */
    public List<TopArticleVO> getOrCompute(Supplier<List<TopArticleVO>> supplier) {
        return cache.get("top10", key -> {
            log.debug("Cache miss for top10, computing...");
            return supplier.get();
        });
    }

    /**
     * 主动失效本地缓存.
     *
     * <p>在以下时机调用:
     * <ul>
     *   <li>收到 Redis Pub/Sub 热度变更消息</li>
     *   <li>热度衰减定时任务执行后</li>
     * </ul>
     */
    public void invalidate() {
        cache.invalidateAll();
        log.debug("Hot articles local cache invalidated");
    }
}

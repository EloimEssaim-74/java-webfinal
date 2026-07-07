package com.kb.article.scheduler;

import com.kb.article.cache.HotArticlesCacheManager;
import com.kb.common.constant.RedisKeyConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 热搜榜单防霸榜 — 每日热度衰减定时任务.
 *
 * <h3>设计目的</h3>
 * <p>纯热度累积会导致旧文章长期霸占榜单，新文章无法上榜。
 * 每日定时对所有文章热度分乘以衰减因子（默认 0.9），
 * 低于阈值（默认 1.0）的条目自动移除，实现"自然降温"效果。</p>
 *
 * <h3>执行频率</h3>
 * <p>默认每日凌晨 3:00 (Cron: {@code 0 0 3 * * ?})，可通过
 * {@code hot-articles.decay-cron} 配置调整.</p>
 *
 * <h3>性能考量</h3>
 * <p>ZSET 遍历为 O(N)，N 为有热度分的文章数。
 * 正常运营下 N 不会超过数千，秒级完成。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeatDecayScheduler {

    private final StringRedisTemplate redisTemplate;
    private final HotArticlesCacheManager cacheManager;

    @Value("${hot-articles.decay-factor:0.9}")
    private double decayFactor;

    @Value("${hot-articles.decay-min-score:1.0}")
    private double minScore;

    @Scheduled(cron = "${hot-articles.decay-cron:0 0 3 * * ?}")
    public void decayScores() {
        log.info("========== 热度衰减开始: factor={}, minScore={} ==========", decayFactor, minScore);

        String key = RedisKeyConstants.HOT_ARTICLES;
        Set<ZSetOperations.TypedTuple<String>> all =
                redisTemplate.opsForZSet().rangeWithScores(key, 0, -1);

        if (all == null || all.isEmpty()) {
            log.info("当前无热搜数据，跳过衰减");
            return;
        }

        int decayed = 0;
        int removed = 0;
        long startTime = System.currentTimeMillis();

        for (ZSetOperations.TypedTuple<String> tuple : all) {
            String member = tuple.getValue();
            if (member == null) continue;

            double oldScore = tuple.getScore() != null ? tuple.getScore() : 0;
            double newScore = oldScore * decayFactor;

            if (newScore < minScore) {
                // 分数过低，从排行榜移除
                redisTemplate.opsForZSet().remove(key, member);
                removed++;
                log.debug("移除冷门文章: articleId={}, oldScore={}", member, oldScore);
            } else {
                // 应用衰减后的分数（ZADD 相同 member 会覆盖分数）
                redisTemplate.opsForZSet().add(key, member, newScore);
                decayed++;
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("========== 热度衰减完成: decayed={}, removed={}, 耗时={}ms ==========",
                decayed, removed, elapsed);

        // 通知所有实例刷新本地缓存
        cacheManager.invalidate();
        redisTemplate.convertAndSend(RedisKeyConstants.HOT_ARTICLES_REFRESH_CHANNEL, "decay");
    }
}

package com.kb.article.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kb.article.cache.HotArticlesCacheManager;
import com.kb.article.mapper.ArticleMapper;
import com.kb.article.service.HotArticleService;
import com.kb.common.constant.RedisKeyConstants;
import com.kb.common.entity.Article;
import com.kb.common.vo.TopArticleVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 热搜榜单服务实现.
 *
 * <h3>架构设计</h3>
 * <pre>
 * 读操作     ──▶ Redis ZSET ZINCRBY +1 ──▶ Pub/Sub 通知
 * 点赞       ──▶ Redis ZSET ZINCRBY +3 ──▶ Pub/Sub 通知
 * 评论       ──▶ Redis ZSET ZINCRBY +1 ──▶ Pub/Sub 通知
 * Top 10 查询 ──▶ Caffeine 本地缓存 (TTL 1s) ──▶ Redis ZSET ──▶ MySQL
 * 热度衰减    ──▶ 定时任务 (每日 3:00) ──▶ Pub/Sub 通知
 * </pre>
 *
 * <h3>缓存策略</h3>
 * <ul>
 *   <li>L1: Caffeine 本地缓存，TTL 1 秒，命中率 > 99%</li>
 *   <li>L2: Redis ZSET {@code hot_articles}，存储 member=articleId, score=热度分</li>
 *   <li>L3: MySQL articles 表，批量补齐标题、点赞数等元数据</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotArticleServiceImpl implements HotArticleService {

    private final StringRedisTemplate redisTemplate;
    private final ArticleMapper articleMapper;
    private final HotArticlesCacheManager cacheManager;

    // ==================== 热度记录 ====================

    @Override
    public void recordRead(Long articleId, Long userId) {
        String dedupKey = String.format(RedisKeyConstants.READ_DEDUP, articleId, userId);
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(dedupKey, "1", Duration.ofSeconds(RedisKeyConstants.READ_DEDUP_TTL_SECONDS));

        if (Boolean.TRUE.equals(isNew)) {
            redisTemplate.opsForZSet().incrementScore(
                    RedisKeyConstants.HOT_ARTICLES,
                    String.valueOf(articleId),
                    1);
            publishRefresh("read:" + articleId);
            log.debug("Hot article read recorded: articleId={}, score +1", articleId);
        }
    }

    @Override
    public void recordLike(Long articleId) {
        redisTemplate.opsForZSet().incrementScore(
                RedisKeyConstants.HOT_ARTICLES,
                String.valueOf(articleId),
                3);
        publishRefresh("like:" + articleId);
        log.debug("Hot article like recorded: articleId={}, score +3", articleId);
    }

    @Override
    public void recordComment(Long articleId) {
        redisTemplate.opsForZSet().incrementScore(
                RedisKeyConstants.HOT_ARTICLES,
                String.valueOf(articleId),
                1);
        publishRefresh("comment:" + articleId);
        log.debug("Hot article comment recorded: articleId={}, score +1", articleId);
    }

    // ==================== Top 10 查询 ====================

    @Override
    @Transactional(readOnly = true)
    public List<TopArticleVO> getTop10() {
        return cacheManager.getOrCompute(this::fetchTop10FromRedis);
    }

    /**
     * 从 Redis ZSET 查询 Top 10 并补齐 MySQL 元数据（缓存穿透时调用）.
     */
    private List<TopArticleVO> fetchTop10FromRedis() {
        Set<ZSetOperations.TypedTuple<String>> top =
                redisTemplate.opsForZSet().reverseRangeWithScores(
                        RedisKeyConstants.HOT_ARTICLES, 0, 9);

        if (top == null || top.isEmpty()) {
            log.debug("hot_articles ZSET is empty");
            return Collections.emptyList();
        }

        // 收集文章 ID
        List<Long> articleIds = top.stream()
                .map(t -> Long.valueOf(Objects.requireNonNull(t.getValue())))
                .collect(Collectors.toList());

        // 批量从 MySQL 查询文章元数据（仅查询已发布且未删除的文章）
        List<Article> articles = articleMapper.selectList(
                new LambdaQueryWrapper<Article>()
                        .in(Article::getId, articleIds)
                        .eq(Article::getStatus, "PUBLISHED")
                        .eq(Article::getDeleted, 0));

        Map<Long, Article> articleMap = articles.stream()
                .collect(Collectors.toMap(Article::getId, a -> a, (a, b) -> a));

        // 按 ZSET 顺序组装结果
        List<TopArticleVO> result = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> t : top) {
            Long articleId = Long.valueOf(Objects.requireNonNull(t.getValue()));
            Article article = articleMap.get(articleId);
            if (article != null) {
                TopArticleVO vo = new TopArticleVO();
                vo.setId(article.getId());
                vo.setTitle(article.getTitle());
                vo.setAuthorId(article.getAuthorId());
                vo.setLikeCount(article.getLikeCount());
                vo.setHeatScore(t.getScore());
                result.add(vo);
            }
        }

        log.debug("Hot articles top10 fetched: size={}", result.size());
        return result;
    }

    // ==================== 私有方法 ====================

    /**
     * 通过 Redis Pub/Sub 广播热度变更事件，触发所有实例刷新本地缓存.
     */
    private void publishRefresh(String source) {
        try {
            redisTemplate.convertAndSend(
                    RedisKeyConstants.HOT_ARTICLES_REFRESH_CHANNEL, source);
        } catch (Exception e) {
            // Pub/Sub 发送失败不影响主流程（缓存会在 TTL 后自动过期）
            log.warn("Failed to publish hot articles refresh event: {}", e.getMessage());
        }
    }
}

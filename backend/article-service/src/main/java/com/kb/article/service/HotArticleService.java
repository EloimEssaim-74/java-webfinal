package com.kb.article.service;

import com.kb.common.vo.TopArticleVO;

import java.util.List;

/**
 * 热搜榜单服务接口.
 *
 * <p>管理基于 Redis ZSET 的文章热度分，支持阅读/点赞/评论三类热度来源，
 * 并提供全站 Top 10 查询。</p>
 */
public interface HotArticleService {

    /**
     * 记录阅读热度（+1），同一用户 5 分钟内重复阅读去重.
     */
    void recordRead(Long articleId, Long userId);

    /**
     * 记录点赞热度（+3）.
     */
    void recordLike(Long articleId);

    /**
     * 记录评论热度（+1）.
     */
    void recordComment(Long articleId);

    /**
     * 获取全站热搜 Top 10，含文章基本信息和热度分.
     */
    List<TopArticleVO> getTop10();
}

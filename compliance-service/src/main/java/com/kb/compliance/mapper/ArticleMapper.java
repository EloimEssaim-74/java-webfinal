package com.kb.compliance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kb.common.entity.Article;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文章 Mapper — 用于合规结果回写.
 *
 * <p>仅用于 {@code selectById} 和 {@code updateById} 操作，
 * 更新 {@code audit_result} 和 {@code deleted} 字段.</p>
 */
@Mapper
public interface ArticleMapper extends BaseMapper<Article> {
}

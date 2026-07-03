package com.kb.tag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kb.common.entity.Article;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文章 Mapper — 用于标签回写.
 *
 * <p>仅用于 {@code selectById} 和 {@code updateById} 操作，
 * 仅更新 {@code tags} 字段.</p>
 */
@Mapper
public interface ArticleMapper extends BaseMapper<Article> {
}

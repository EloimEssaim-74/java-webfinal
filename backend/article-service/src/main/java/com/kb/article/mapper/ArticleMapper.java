package com.kb.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kb.common.entity.Article;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ArticleMapper extends BaseMapper<Article> {

    @Update("UPDATE articles SET like_count = like_count + 1 WHERE id = #{articleId}")
    int incrementLikeCount(@Param("articleId") Long articleId);

    @Update("UPDATE articles SET status = 'PUBLISHED', updated_at = NOW() WHERE id = #{articleId} AND deleted = 0")
    int publishById(@Param("articleId") Long articleId);
}

package com.kb.interact.service;

import com.kb.common.vo.CommentVO;

import java.util.List;

public interface CommentService {

    void createComment(Long articleId, Long userId, String content);

    List<CommentVO> listByArticleId(Long articleId);
}

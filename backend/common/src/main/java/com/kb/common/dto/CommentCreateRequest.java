package com.kb.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CommentCreateRequest {

    @NotNull(message = "文章ID不能为空")
    private Long articleId;

    @NotBlank(message = "评论内容不能为空")
    @Size(max = 5000, message = "评论内容不能超过5000个字符")
    private String content;
}

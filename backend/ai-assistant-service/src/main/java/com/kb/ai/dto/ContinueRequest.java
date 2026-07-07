package com.kb.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 续写请求体.
 *
 * <p>context — 上文内容，AI 基于此生成后续文本.</p>
 */
@Data
public class ContinueRequest {

    /** 上文内容（必填，最长 4000 字） */
    @NotBlank(message = "上下文内容不能为空")
    @Size(max = 4000, message = "上下文内容不能超过 4000 字")
    private String context;
}

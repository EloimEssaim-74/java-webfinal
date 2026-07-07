package com.kb.common.vo;

import lombok.Data;

import java.util.List;

/**
 * 分页响应包装.
 *
 * <p>替代 {@code Result<List<T>>} 用于需要分页元数据的列表接口.</p>
 */
@Data
public class PageResult<T> {

    /** 当前页数据 */
    private List<T> list;

    /** 总记录数 */
    private long total;

    /** 当前页码 */
    private int page;

    /** 每页大小 */
    private int size;
}

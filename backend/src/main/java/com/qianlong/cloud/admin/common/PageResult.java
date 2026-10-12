package com.qianlong.cloud.admin.common;

import java.util.List;

/**
 * 分页结果 —— 与前端 {@code src/api/types.ts} 的 {@code PageResult<T>} 一致。
 *
 * <p>前端表格用的是 {@code remote} 模式，只认 {@code list} 与 {@code total}；
 * {@code page} / {@code pageSize} 原样回传便于排查。
 */
public record PageResult<T>(List<T> list, long total, long page, long pageSize) {

    public static <T> PageResult<T> of(List<T> list, long total, long page, long pageSize) {
        return new PageResult<>(list, total, page, pageSize);
    }

    public static <T> PageResult<T> empty(long page, long pageSize) {
        return new PageResult<>(List.of(), 0L, page, pageSize);
    }
}

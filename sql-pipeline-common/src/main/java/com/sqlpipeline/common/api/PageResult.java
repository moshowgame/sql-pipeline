package com.sqlpipeline.common.api;

import java.util.List;

/** 分页结果。 */
public record PageResult<T>(long total, long page, long size, List<T> items) {

    public static <T> PageResult<T> of(long total, long page, long size, List<T> items) {
        return new PageResult<>(total, page, size, items);
    }
}

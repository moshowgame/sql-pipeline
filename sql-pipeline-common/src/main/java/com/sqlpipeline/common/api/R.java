package com.sqlpipeline.common.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.MDC;

/** 统一响应体：{code, message, data, traceId}，成功码为 "0"。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class R<T> {

    public static final String OK = "0";

    private String code;
    private String message;
    private T data;
    private String traceId;

    public static <T> R<T> ok(T data) {
        return new R<>(OK, "ok", data, MDC.get("traceId"));
    }

    public static R<Void> ok() {
        return ok(null);
    }

    public static <T> R<T> fail(String code, String message) {
        return new R<>(code, message, null, MDC.get("traceId"));
    }
}

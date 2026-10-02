package com.sqlpipeline.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 新建连接请求。 */
public record ConnectionCreateReq(
        @NotBlank @Size(max = 64) String connKey,
        @NotBlank String displayName,
        @NotBlank String jdbcUrl,
        @NotBlank String username,
        @NotBlank String password,
        String driverClass,
        Integer poolSize,
        Integer connTimeoutMs,
        Integer maxRows,
        Integer queryTimeoutS,
        Integer enabled) {
}

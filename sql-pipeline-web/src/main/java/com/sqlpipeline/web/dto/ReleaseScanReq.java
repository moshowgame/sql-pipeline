package com.sqlpipeline.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 目录结构扫描请求：按 release path 扫描数字子目录（asc）。 */
public record ReleaseScanReq(
        String releaseType,
        @NotBlank String releasePath,
        String defaultConnKey) {
}

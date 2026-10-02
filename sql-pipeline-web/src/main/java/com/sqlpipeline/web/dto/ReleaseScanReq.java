package com.sqlpipeline.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** 目录结构预览请求。 */
public record ReleaseScanReq(
        @NotBlank String planName,
        String defaultConnKey,
        List<StepConfigReq> steps) {
}

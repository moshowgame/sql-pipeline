package com.sqlpipeline.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 创建发布计划（DRAFT）。 */
public record ReleaseCreateReq(
        @NotBlank @Size(max = 128) String planName,
        String defaultConnKey,
        List<StepConfigReq> steps) {
}

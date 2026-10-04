package com.sqlpipeline.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 创建发布计划（DRAFT）。releasePath 必填：FOLDER 类型为发布目录（支持 share folder），ZIP 类型为 zip 包路径（解压暂未实现）。 */
public record ReleaseCreateReq(
        @NotBlank @Size(max = 128) String planName,
        String releaseType,
        @NotBlank String releasePath,
        String defaultConnKey,
        List<StepConfigReq> steps) {
}

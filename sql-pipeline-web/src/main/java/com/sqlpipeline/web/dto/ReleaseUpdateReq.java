package com.sqlpipeline.web.dto;

import java.util.List;

/** 编辑发布计划请求（仅 DRAFT 可编辑）：planName 不可改。 */
public record ReleaseUpdateReq(
        String releaseType,
        String releasePath,
        String defaultConnKey,
        List<StepConfigReq> steps) {
}

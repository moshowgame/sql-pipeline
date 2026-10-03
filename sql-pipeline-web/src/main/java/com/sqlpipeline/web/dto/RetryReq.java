package com.sqlpipeline.web.dto;

import jakarta.validation.constraints.Size;

/** 单步重试请求（备注可选，写入 retry_remark 审计）。 */
public record RetryReq(@Size(max = 512) String remark) {
}

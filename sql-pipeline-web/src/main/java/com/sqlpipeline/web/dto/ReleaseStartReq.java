package com.sqlpipeline.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 启动发布：CR 单号 + 备注（RL-5）。 */
public record ReleaseStartReq(
        @NotBlank @Size(max = 64) String crNumber,
        @NotBlank @Size(max = 512) String remark) {
}

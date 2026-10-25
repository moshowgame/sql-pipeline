package com.sqlpipeline.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 登录请求。 */
public record LoginReq(
        @NotBlank String username,
        @NotBlank String password) {
}

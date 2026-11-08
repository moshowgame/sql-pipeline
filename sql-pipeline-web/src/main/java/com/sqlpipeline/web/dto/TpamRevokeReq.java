package com.sqlpipeline.web.dto;

import jakarta.validation.constraints.NotBlank;

/** self-TPAM 临时账号吊销请求。 */
public record TpamRevokeReq(@NotBlank String username) {
}

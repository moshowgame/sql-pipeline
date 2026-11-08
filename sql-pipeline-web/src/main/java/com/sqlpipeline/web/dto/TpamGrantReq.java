package com.sqlpipeline.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * self-TPAM 临时账号申请请求：
 * @param crInNo  CR/IN 变更单号（审计追溯）
 * @param staffId 申请人 staffid（生成账号 staffid_tpam；必须是系统中不存在的账号）
 * @param role    授权角色：ADMIN（读写）/ VIEWER（只读）
 */
public record TpamGrantReq(
        @NotBlank String crInNo,
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9_-]{2,64}") String staffId,
        @Pattern(regexp = "ADMIN|VIEWER") String role) {
}

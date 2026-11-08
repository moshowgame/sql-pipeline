package com.sqlpipeline.web.auth;

import lombok.Data;

import java.time.LocalDateTime;

/** 平台用户（同时作为 MyBatis 结果映射对象）。 */
@Data
public class UserAccount {

    private String username;

    /** 加密后的密码（默认 BCrypt），绝不下发到前端。 */
    private String passwordHash;

    private String displayName;

    /** 角色：ADMIN 可读写 / VIEWER 只读。 */
    private String role;

    /** 临时账号(self-TPAM)失效时间；NULL 表示永久账号。 */
    private LocalDateTime expiresAt;

    /** 临时账号关联的 CR/IN 变更单号。 */
    private String crInNo;

    /** 临时账号申请人的 staffid。 */
    private String staffId;

    /** 1 启用 / 0 禁用。 */
    private Integer enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

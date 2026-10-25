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

    /** 1 启用 / 0 禁用。 */
    private Integer enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

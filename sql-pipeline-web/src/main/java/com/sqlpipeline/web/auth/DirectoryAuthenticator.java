package com.sqlpipeline.web.auth;

import java.util.Optional;

/**
 * 外部目录认证 SPI：LDAP/AD 等企业目录实现本接口并注册为 Spring Bean 即可启用
 * （参考实现见扩展模块 sql-pipeline-ldap；AuthService 通过 ObjectProvider 注入，无 Bean 时走本地认证）。
 */
public interface DirectoryAuthenticator {

    /**
     * 认证：验证用户名/密码是否为本目录用户。
     * 成功返回目录用户（含开通时建议的角色）；凭据错误返回 empty；
     * 目录服务不可用时由实现方决定：抛 BizException(AU_LDAP_UNAVAILABLE)（failClosed）或返回 empty（降级回退本地）。
     */
    Optional<DirectoryUser> authenticate(String username, String password);

    /** 目录用户：username 全局唯一标识；displayName 展示名（可能为空）；role 为首次开通时的建议角色。 */
    record DirectoryUser(String username, String displayName, String role) {
    }
}

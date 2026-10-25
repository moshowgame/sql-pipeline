package com.sqlpipeline.web.auth;

import java.util.Optional;

/**
 * 用户来源公共接口：默认实现从平台元数据库 platform_user 表加载。
 * 接入自有认证系统（LDAP/OAuth/内部账号中心）时，实现本接口并注册为 Spring Bean 即可整体替换。
 */
public interface UserStore {

    /** 按用户名加载用户；不存在返回 empty。 */
    Optional<UserAccount> findByUsername(String username);

    /** 创建用户（passwordHash 已由 PasswordEncryptor 加密）。 */
    void createUser(UserAccount user);
}

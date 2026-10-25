package com.sqlpipeline.web.auth;

import java.util.Optional;

/**
 * 令牌公共接口：签发 / 校验。
 * 默认实现 {@link HmacTokenManager}（HMAC 签名 + 内存缓存）；接 JWT 或分布式缓存时实现本接口替换即可。
 */
public interface TokenManager {

    /** 为用户签发新令牌。 */
    String issue(String username);

    /** 校验令牌，有效返回用户名（过期/被登出/伪造一律视为无效）。 */
    Optional<String> verify(String token);

    /** 作废令牌（登出）。 */
    void revoke(String token);
}

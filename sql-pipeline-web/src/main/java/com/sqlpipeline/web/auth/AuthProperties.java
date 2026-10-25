package com.sqlpipeline.web.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证配置（sql-pipeline.auth）。
 * <p>接入自有认证系统时的常见改法：
 * <ul>
 *   <li>换用户来源：实现 {@link UserStore} 并注册为 Spring Bean（默认 {@link DbUserStore} 自动让位）；</li>
 *   <li>换密码算法：实现 {@link PasswordEncryptor} 并注册为 Spring Bean；</li>
 *   <li>换令牌方案（如接 JWT/Redis）：实现 {@link TokenManager} 并注册为 Spring Bean。</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "sql-pipeline.auth")
public class AuthProperties {

    /** 是否启用登录认证；false 时所有 /api 接口放开（与历史无登录行为一致）。 */
    private boolean enabled = true;

    /** Token 签名密钥（HMAC-SHA256），生产环境务必通过环境变量覆盖。 */
    private String tokenSecret = "sql-pipeline-default-secret-please-change";

    /** Token 有效期，同时决定 Cookie 有效期（默认 1 天）。 */
    private Duration tokenTtl = Duration.ofDays(1);

    /** 认证 Cookie 名。 */
    private String cookieName = "AUTH_TOKEN";

    /**
     * 内置用户（用户名 -> 明文密码）：启动时不存在则自动创建，密码入库前经 PasswordEncryptor 加密。
     * 快速添加用户只需在此加一行，例如 dev: dev123。
     */
    private Map<String, String> defaultUsers = new LinkedHashMap<>(Map.of(
            "admin", "admin123",
            "dev", "dev123"));
}

package com.sqlpipeline.web.auth;

import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.springframework.util.StringUtils.hasText;

/**
 * 认证服务：登录 / 登出 / 当前用户。
 * 组合四个可替换组件：{@link DirectoryAuthenticator}（外部目录 SPI，可选）+ {@link UserStore}（用户来源）
 * + {@link PasswordEncryptor}（密码算法）+ {@link TokenManager}（令牌）。
 * <p>目录认证（如 LDAP 扩展模块）优先：目录凭据正确时自动开通或沿用本地档案签发 Token；
 * 目录无此用户/凭据错误/未启用/不可用（fail-open 策略）时回退本地 BCrypt 校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 目录账号在本地的密码占位标记：BCrypt 遇非 $2a$ 前缀恒不匹配，且 DBA 可一眼识别该账号由目录管理。 */
    public static final String LDAP_PASSWORD_MARKER = "{LDAP}";

    private final UserStore userStore;
    private final PasswordEncryptor passwordEncryptor;
    private final TokenManager tokenManager;
    /** 外部目录认证 SPI（ObjectProvider 可选注入）：LDAP 扩展模块启用时存在，否则为空。 */
    private final ObjectProvider<DirectoryAuthenticator> directoryAuthenticator;

    /** 登录：目录认证优先 → 本地密码校验，成功签发 Token。失败抛 AU_LOGIN_FAILED / AU_USER_DISABLED（401/403）。 */
    public LoginResult login(String username, String rawPassword) {
        UserAccount user = userStore.findByUsername(username).orElse(null);

        DirectoryAuthenticator.DirectoryUser dirUser = authenticateViaDirectory(username, rawPassword);
        if (dirUser != null) {
            if (user == null) {
                user = provision(dirUser);
            }
            assertLoginAllowed(user);
            return new LoginResult(tokenManager.issue(username, user.getRole()), user);
        }

        // 本地认证路径（目录未启用/无此用户/凭据错误/不可用降级）
        if (user == null) {
            throw BizException.i18n(ErrorCode.AU_LOGIN_FAILED, "error.auth.loginFailed");
        }
        assertLoginAllowed(user);
        if (!passwordEncryptor.matches(rawPassword, user.getPasswordHash())) {
            throw BizException.i18n(ErrorCode.AU_LOGIN_FAILED, "error.auth.loginFailed");
        }
        return new LoginResult(tokenManager.issue(username, user.getRole()), user);
    }

    /** 登出：吊销 Token。 */
    public void logout(String token) {
        tokenManager.revoke(token);
    }

    /** 由 Token 解析当前用户名（拦截器认证通过后再查用户信息用）。 */
    public Optional<UserAccount> currentUser(String token) {
        return tokenManager.verify(token).flatMap(p -> userStore.findByUsername(p.username()));
    }

    /** 登录结果：Token + 用户信息。 */
    public record LoginResult(String token, UserAccount user) {
    }

    // ---------- 目录认证 ----------

    /** 目录认证：SPI Bean 缺失 / 目录凭据错误 → null 走本地；failClosed 的 AU_LDAP_UNAVAILABLE 直接上抛。 */
    private DirectoryAuthenticator.DirectoryUser authenticateViaDirectory(String username, String rawPassword) {
        DirectoryAuthenticator authenticator = directoryAuthenticator.getIfAvailable();
        if (authenticator == null) {
            return null;
        }
        try {
            return authenticator.authenticate(username, rawPassword).orElse(null);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("目录认证异常，回退本地认证: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** 目录用户首次登录自动开通：本地无该账号时按目录建议角色建档，密码占位为 {LDAP} 标记。 */
    private UserAccount provision(DirectoryAuthenticator.DirectoryUser dirUser) {
        UserAccount account = new UserAccount();
        account.setUsername(dirUser.username());
        account.setPasswordHash(LDAP_PASSWORD_MARKER);
        account.setDisplayName(hasText(dirUser.displayName()) ? dirUser.displayName() : dirUser.username());
        account.setRole(dirUser.role());
        account.setEnabled(1);
        userStore.createUser(account);
        log.info("目录用户首次登录已自动开通: username={}, role={}", dirUser.username(), dirUser.role());
        return account;
    }

    /** 登录前置校验：本地禁用与临时账号过期对目录登录同样生效。 */
    private void assertLoginAllowed(UserAccount user) {
        if (user.getEnabled() != null && user.getEnabled() != 1) {
            throw BizException.i18n(ErrorCode.AU_USER_DISABLED, "error.auth.userDisabled");
        }
        if (user.getExpiresAt() != null && user.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw BizException.i18n(ErrorCode.AU_TPAM_EXPIRED, "error.tpam.expired", user.getUsername());
        }
    }
}

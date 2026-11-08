package com.sqlpipeline.web.auth;

import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 认证服务：登录 / 登出 / 当前用户。
 * 组合三个可替换组件：{@link UserStore}（用户来源）+ {@link PasswordEncryptor}（密码算法）+ {@link TokenManager}（令牌）。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserStore userStore;
    private final PasswordEncryptor passwordEncryptor;
    private final TokenManager tokenManager;

    /** 登录：校验密码并签发 Token。失败抛 AU_LOGIN_FAILED / AU_USER_DISABLED（401/403）。 */
    public LoginResult login(String username, String rawPassword) {
        UserAccount user = userStore.findByUsername(username)
                .orElseThrow(() -> BizException.i18n(ErrorCode.AU_LOGIN_FAILED, "error.auth.loginFailed"));
        if (user.getEnabled() != null && user.getEnabled() != 1) {
            throw BizException.i18n(ErrorCode.AU_USER_DISABLED, "error.auth.userDisabled");
        }
        // self-TPAM 临时账号过期：直接拒绝登录（到点删除由 TpamUserCleanup 完成）
        if (user.getExpiresAt() != null && user.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw BizException.i18n(ErrorCode.AU_TPAM_EXPIRED, "error.tpam.expired", user.getUsername());
        }
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
}

package com.sqlpipeline.web.auth;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/**
 * 认证拦截器：每个 /api 请求都从 Cookie（或 Authorization: Bearer）取 Token 校验，
 * 通过后把用户名放入 request attribute（{@link #ATTR_USERNAME}），失败抛 401。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_USERNAME = "auth.username";

    private final AuthProperties authProperties;
    private final TokenManager tokenManager;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!authProperties.isEnabled()) {
            return true;
        }
        String token = resolveToken(request);
        String username = tokenManager.verify(token)
                .orElseThrow(() -> BizException.i18n(ErrorCode.AU_UNAUTHORIZED, "error.auth.unauthorized"));
        request.setAttribute(ATTR_USERNAME, username);
        return true;
    }

    /** Token 取值优先级：AUTH_TOKEN Cookie > Authorization: Bearer 头。 */
    private String resolveToken(HttpServletRequest request) {
        return cookieValue(request, authProperties.getCookieName())
                .or(() -> bearerToken(request))
                .orElse(null);
    }

    private Optional<String> cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    private Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return Optional.of(header.substring(7).trim());
        }
        return Optional.empty();
    }
}

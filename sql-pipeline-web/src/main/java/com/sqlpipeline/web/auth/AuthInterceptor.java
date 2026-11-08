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
 * 通过后把用户名与角色放入 request attribute（{@link #ATTR_USERNAME} / {@link #ATTR_ROLE}），失败抛 401。
 * <p>只读角色（VIEWER）：非安全方法（GET/HEAD/OPTIONS）的写请求一律 403（登出除外）——
 * support team 查询专用，防止误操作发布流程。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_USERNAME = "auth.username";
    public static final String ATTR_ROLE = "auth.role";

    private final AuthProperties authProperties;
    private final TokenManager tokenManager;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!authProperties.isEnabled()) {
            return true;
        }
        String token = resolveToken(request);
        TokenManager.TokenPrincipal principal = tokenManager.verify(token)
                .orElseThrow(() -> BizException.i18n(ErrorCode.AU_UNAUTHORIZED, "error.auth.unauthorized"));
        if (principal.isViewer()) {
            enforceReadOnly(request);
        }
        request.setAttribute(ATTR_USERNAME, principal.username());
        request.setAttribute(ATTR_ROLE, principal.role());
        return true;
    }

    /** VIEWER 只放行查询方法与登出，其余写操作 403（AU0004）。 */
    private void enforceReadOnly(HttpServletRequest request) {
        String method = request.getMethod();
        boolean safe = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
        boolean logout = "POST".equals(method) && request.getRequestURI().endsWith("/api/auth/logout");
        if (!safe && !logout) {
            throw BizException.i18n(ErrorCode.AU_READ_ONLY, "error.auth.readOnly");
        }
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

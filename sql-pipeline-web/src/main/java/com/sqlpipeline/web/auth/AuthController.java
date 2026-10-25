package com.sqlpipeline.web.auth;

import com.sqlpipeline.common.api.R;
import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.web.auth.AuthService.LoginResult;
import com.sqlpipeline.web.dto.LoginReq;
import com.sqlpipeline.web.dto.UserView;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 登录认证接口：login / logout / me。 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthProperties authProperties;

    @PostMapping("/login")
    public R<UserView> login(@Valid @RequestBody LoginReq req, HttpServletResponse response) {
        LoginResult result = authService.login(req.username(), req.password());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookie(result.token()).build().toString());
        return R.ok(UserView.of(result.user()));
    }

    @PostMapping("/logout")
    public R<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = currentToken(request);
        if (token != null) {
            authService.logout(token);
        }
        // maxAge=0 让浏览器立即删除 Cookie
        response.addHeader(HttpHeaders.SET_COOKIE, authCookie("").maxAge(0).build().toString());
        return R.ok();
    }

    @GetMapping("/me")
    public R<UserView> me(HttpServletRequest request) {
        String token = currentToken(request);
        return authService.currentUser(token)
                .map(user -> R.ok(UserView.of(user)))
                .orElse(R.fail(ErrorCode.AU_UNAUTHORIZED.getCode(), ErrorCode.AU_UNAUTHORIZED.getDefaultMessage()));
    }

    private ResponseCookie.ResponseCookieBuilder authCookie(String value) {
        return ResponseCookie.from(authProperties.getCookieName(), value)
                .httpOnly(true)
                .path("/")
                .maxAge(authProperties.getTokenTtl().getSeconds())
                .sameSite("Lax");
    }

    private String currentToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (authProperties.getCookieName().equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}

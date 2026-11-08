package com.sqlpipeline.web.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 认证拦截器注册：仅拦 /api/**，静态页面不在 /api 下天然放行。@EnableScheduling 供 TPAM 过期清理等任务使用。 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class AuthWebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // 登录接口本身放行
                .excludePathPatterns("/api/auth/login");
    }
}

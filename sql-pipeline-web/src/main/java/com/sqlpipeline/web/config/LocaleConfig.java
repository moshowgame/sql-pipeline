package com.sqlpipeline.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.util.Locale;

/**
 * i18n 配置：
 * <ul>
 *   <li>默认英文（CookieLocaleResolver.defaultLocale = EN，不跟随浏览器 Accept-Language）</li>
 *   <li>语言偏好存 LANG Cookie；前端切换器写 Cookie，后端错误消息同步生效</li>
 *   <li>支持 URL 参数切换：?lang=zh（写回 Cookie）</li>
 * </ul>
 */
@Configuration
public class LocaleConfig implements WebMvcConfigurer {

    public static final String LANG_COOKIE = "LANG";

    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver(LANG_COOKIE);
        resolver.setDefaultLocale(Locale.ENGLISH);
        resolver.setCookieMaxAge(365 * 24 * 3600);
        resolver.setCookiePath("/");
        return resolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");
        registry.addInterceptor(interceptor);
    }
}

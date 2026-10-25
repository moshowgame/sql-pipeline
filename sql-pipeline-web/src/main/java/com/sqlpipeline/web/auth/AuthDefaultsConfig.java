package com.sqlpipeline.web.auth;

import com.sqlpipeline.web.auth.mapper.PlatformUserMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 认证默认组件装配。
 * <p>接入自有认证系统：声明自己的 {@link UserStore} / {@link PasswordEncryptor} / {@link TokenManager}
 * Bean 即可覆盖默认实现（@ConditionalOnMissingBean 自动让位）。
 * <p>单独一个配置类，避免与注册拦截器的 {@link AuthWebConfig} 形成循环依赖。
 */
@Configuration
public class AuthDefaultsConfig {

    @Bean
    @ConditionalOnMissingBean(PasswordEncryptor.class)
    public PasswordEncryptor passwordEncryptor() {
        return new BCryptPasswordEncryptor();
    }

    @Bean
    @ConditionalOnMissingBean(TokenManager.class)
    public TokenManager tokenManager(AuthProperties properties) {
        return new HmacTokenManager(properties);
    }

    @Bean
    @ConditionalOnMissingBean(UserStore.class)
    public UserStore userStore(PlatformUserMapper mapper) {
        return new DbUserStore(mapper);
    }
}

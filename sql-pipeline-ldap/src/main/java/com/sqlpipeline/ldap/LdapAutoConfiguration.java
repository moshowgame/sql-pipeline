package com.sqlpipeline.ldap;

import com.sqlpipeline.web.auth.DirectoryAuthenticator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LDAP 扩展模块装配：由 boot 的 scanBasePackages("com.sqlpipeline") 自动发现，无需 spring.factories。
 * 仅当 sql-pipeline.auth.ldap.enabled=true 时注册 {@link DirectoryAuthenticator} Bean；
 * 默认关闭（Bean 不存在），AuthService 走本地认证，行为与历史完全一致。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LdapProperties.class)
public class LdapAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "sql-pipeline.auth.ldap.enabled", havingValue = "true")
    public DirectoryAuthenticator directoryAuthenticator(LdapProperties properties) {
        return new LdapAuthenticator(properties);
    }
}

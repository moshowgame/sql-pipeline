package com.sqlpipeline.web.auth;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 默认用户播种：启动时把 sql-pipeline.auth.default-users 中不存在的用户写入 platform_user 表
 * （密码经 PasswordEncryptor 加密后入库）。快速添加用户只需在 application.yml 加一行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultUserSeeder {

    private final AuthProperties authProperties;
    private final UserStore userStore;
    private final PasswordEncryptor passwordEncryptor;

    @PostConstruct
    public void seed() {
        authProperties.getDefaultUsers().forEach((username, rawPassword) -> {
            if (userStore.findByUsername(username).isPresent()) {
                return;
            }
            UserAccount user = new UserAccount();
            user.setUsername(username);
            user.setPasswordHash(passwordEncryptor.encrypt(rawPassword));
            user.setDisplayName(username);
            user.setRole("ADMIN");
            user.setEnabled(1);
            user.setCreatedAt(LocalDateTime.now());
            userStore.createUser(user);
            log.info("seeded default user: {}", username);
        });
    }
}

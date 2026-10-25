package com.sqlpipeline.web.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 默认密码加密实现：BCrypt（自带随机盐，可抵御彩虹表）。 */
public class BCryptPasswordEncryptor implements PasswordEncryptor {

    private final BCryptPasswordEncoder delegate = new BCryptPasswordEncoder();

    @Override
    public String encrypt(String rawPassword) {
        return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String encryptedPassword) {
        return encryptedPassword != null && !encryptedPassword.isBlank()
                && delegate.matches(rawPassword, encryptedPassword);
    }
}

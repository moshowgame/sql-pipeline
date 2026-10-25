package com.sqlpipeline.web.auth;

/** 密码加密公共接口：接入方只需实现本接口即可替换加密算法（如接入自有密码体系）。 */
public interface PasswordEncryptor {

    /** 加密明文密码（含盐，结果可直接入库）。 */
    String encrypt(String rawPassword);

    /** 校验明文密码与库中密文是否匹配。 */
    boolean matches(String rawPassword, String encryptedPassword);
}

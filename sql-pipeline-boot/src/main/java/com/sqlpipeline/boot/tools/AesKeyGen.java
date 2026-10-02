package com.sqlpipeline.boot.tools;

import java.security.SecureRandom;
import java.util.Base64;

/** 生成 AES-256 密钥（Base64），用于 SQL_PIPELINE_AES_KEY。 */
public final class AesKeyGen {

    private AesKeyGen() {
    }

    public static void main(String[] args) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        System.out.println(Base64.getEncoder().encodeToString(key));
    }
}

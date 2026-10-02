package com.sqlpipeline.datasource.crypto;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 连接凭据加密：AES-256-GCM，随机 12 字节 IV 前置，Base64 存储；密钥不落库（环境变量 / KMS）。
 */
@Component
public class CryptoService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(CryptoProperties properties) {
        String raw = properties.getKey();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("缺少 AES 密钥：请设置环境变量 SQL_PIPELINE_AES_KEY"
                    + "（生成方式：openssl rand -base64 32）");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("AES 密钥不是合法的 Base64 编码", e);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException("AES 密钥必须为 32 字节（Base64 编码后约 44 字符），当前 " + keyBytes.length + " 字节");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYS_INTERNAL, "密码加密失败", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYS_INTERNAL, "密码解密失败（密钥是否与加密时一致？）", e);
        }
    }
}

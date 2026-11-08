package com.sqlpipeline.web.auth;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认令牌实现：HMAC-SHA256 签名 + 内存缓存（有效期与 Token 一致，默认 1 天）。
 * <p>格式：base64url(username).base64url(role).过期毫秒.签名；认证以缓存为准——签名只防伪造，
 * 缓存提供 1 天内的快速校验并支持登出即吊销（应用重启后所有令牌失效，需重新登录）。
 */
@Slf4j
public class HmacTokenManager implements TokenManager {

    private static final String HMAC_ALGO = "HmacSHA256";
    /** 缓存清理扫描间隔：避免每次请求都全表扫描过期条目。 */
    private static final long SWEEP_INTERVAL_MS = 10 * 60 * 1000L;

    private final byte[] secretKey;
    private final long ttlMillis;

    /** token -> 缓存条目（内存态令牌缓存）。 */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private volatile long lastSweepAt = System.currentTimeMillis();

    private record CacheEntry(String username, String role, long expireAt) {
        boolean expired() {
            return System.currentTimeMillis() >= expireAt;
        }
    }

    public HmacTokenManager(AuthProperties properties) {
        // 用 SHA-256 归一化任意长度的密钥，保证 HMAC 密钥恒为 32 字节
        this.secretKey = sha256(properties.getTokenSecret());
        this.ttlMillis = properties.getTokenTtl().toMillis();
    }

    @Override
    public String issue(String username, String role) {
        String safeRole = role == null || role.isBlank() ? "ADMIN" : role;
        long expireAt = System.currentTimeMillis() + ttlMillis;
        String payload = encode(username) + "." + encode(safeRole) + "." + expireAt;
        String token = payload + "." + sign(payload);
        cache.put(token, new CacheEntry(username, safeRole, expireAt));
        sweepIfDue();
        return token;
    }

    @Override
    public Optional<TokenPrincipal> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        CacheEntry entry = cache.get(token);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expired()) {
            cache.remove(token);
            return Optional.empty();
        }
        return Optional.of(new TokenPrincipal(entry.username(), entry.role()));
    }

    @Override
    public void revoke(String token) {
        if (token != null) {
            cache.remove(token);
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGO));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 签名失败", e);
        }
    }

    private String encode(String username) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(username.getBytes(StandardCharsets.UTF_8));
    }

    /** 低频清理过期条目，防止长期运行内存缓慢增长。 */
    private void sweepIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastSweepAt < SWEEP_INTERVAL_MS) {
            return;
        }
        lastSweepAt = now;
        cache.entrySet().removeIf(e -> e.getValue().expired());
        log.debug("token cache swept, remaining={}", cache.size());
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

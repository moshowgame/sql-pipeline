package com.sqlpipeline.web.auth;

import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.web.auth.mapper.PlatformUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * self-TPAM 自助临时账号服务（support 场景）：
 * <p>申请人提供 CR/IN 变更单号 + 本人 staffid，系统生成 {@code staffid_tpam} 临时账号
 * （32 位随机密码、默认 4 小时有效、权限可选 ADMIN/VIEWER）。规则：
 * <ul>
 *   <li>user_id 必须是系统中不存在的账号（避免覆盖正式账号）；过期/吊销后自动删除，可重新申请；</li>
 *   <li>密码明文只在申请响应中返回一次，入库前经 PasswordEncryptor 加密；</li>
 *   <li>审计：CR/IN 单号 + 申请人随账号入库（platform_user.cr_in_no / staff_id），申请/吊销/回收动作记录应用日志。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TempUserService {

    /** 临时账号用户名后缀。 */
    public static final String SUFFIX = "_tpam";

    /** 密码字符集：大小写字母 + 数字，长度 32（约 190 bit 熵）。 */
    private static final String PASSWORD_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int PASSWORD_LENGTH = 32;

    private static final Set<String> ALLOWED_ROLES = Set.of("ADMIN", "VIEWER");

    private final AuthProperties authProperties;
    private final PasswordEncryptor passwordEncryptor;
    private final PlatformUserMapper mapper;
    private final SecureRandom random = new SecureRandom();

    /** 申请临时账号。返回结果中的明文密码仅此一次，不再提供查询。operator 为当前登录管理员。 */
    public TpamGrantResult grant(String crInNo, String staffId, String role, String operator) {
        AuthProperties.Tpam cfg = authProperties.getTpam();
        if (!cfg.isEnabled()) {
            throw BizException.i18n(ErrorCode.AU_TPAM_INVALID, "error.tpam.disabled");
        }
        if (crInNo == null || crInNo.isBlank() || staffId == null || staffId.isBlank()
                || role == null || !ALLOWED_ROLES.contains(role.toUpperCase())) {
            throw BizException.i18n(ErrorCode.AU_TPAM_INVALID, "error.tpam.invalid");
        }
        String safeStaffId = staffId.trim();
        String username = safeStaffId + SUFFIX;
        if (mapper.selectByUsername(username) != null) {
            throw BizException.i18n(ErrorCode.AU_TPAM_EXISTS, "error.tpam.exists", username);
        }
        String rawPassword = randomPassword();
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(cfg.getTtlHours());
        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setPasswordHash(passwordEncryptor.encrypt(rawPassword));
        user.setDisplayName("TPAM " + crInNo.trim() + " / " + safeStaffId);
        user.setRole(role.toUpperCase());
        user.setExpiresAt(expiresAt);
        user.setCrInNo(crInNo.trim());
        user.setStaffId(safeStaffId);
        user.setEnabled(1);
        mapper.insert(user);
        log.info("TPAM account granted: username={}, role={}, crInNo={}, staffId={}, expiresAt={}, operator={}",
                username, user.getRole(), user.getCrInNo(), safeStaffId, expiresAt, operator);
        return new TpamGrantResult(username, rawPassword, user.getRole(), crInNo.trim(), expiresAt);
    }

    /** 提前吊销：直接删除临时账号并释放 staffid。 */
    public void revoke(String username, String operator) {
        if (username == null || !username.endsWith(SUFFIX)) {
            throw BizException.i18n(ErrorCode.AU_TPAM_INVALID, "error.tpam.notTemp", username);
        }
        if (mapper.deleteTempUser(username) == 0) {
            throw BizException.i18n(ErrorCode.AU_TPAM_INVALID, "error.tpam.notTemp", username);
        }
        log.info("TPAM account revoked: username={}, operator={}", username, operator);
    }

    /** 现存临时账号列表（含未到期的全部 *_tpam 账号）。 */
    public List<UserAccount> list() {
        return mapper.selectTempUsers();
    }

    /** 到点回收（由 {@link TpamUserCleanup} 定时调用）：删除过期账号并记录审计日志。 */
    public int cleanupExpired() {
        int removed = mapper.deleteExpired();
        if (removed > 0) {
            log.info("TPAM expired accounts cleaned: count={}", removed);
        }
        return removed;
    }

    /** 32 位随机密码：SecureRandom 逐位取样，保证任意位置字符均匀分布。 */
    private String randomPassword() {
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }

    /** 申请结果：明文密码仅随本次响应返回一次。 */
    public record TpamGrantResult(String username, String password, String role, String crInNo, LocalDateTime expiresAt) {
    }
}

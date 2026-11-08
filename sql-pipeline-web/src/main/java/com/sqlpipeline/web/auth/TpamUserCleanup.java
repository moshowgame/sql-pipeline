package com.sqlpipeline.web.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * self-TPAM 临时账号过期回收任务：每 5 分钟扫描一次，删除 expires_at 已到点的临时账号，
 * 释放 staffid 以便重新申请。删除动作记录应用日志（配合 cr_in_no 做审计追溯）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TpamUserCleanup {

    private final TempUserService tempUserService;

    /** fixedDelay：上轮跑完再计时，避免执行慢导致堆积。 */
    @Scheduled(fixedDelay = 5 * 60 * 1000L, initialDelay = 60 * 1000L)
    public void cleanup() {
        try {
            tempUserService.cleanupExpired();
        } catch (Exception ex) {
            log.warn("TPAM cleanup failed: {}", ex.getMessage());
        }
    }
}

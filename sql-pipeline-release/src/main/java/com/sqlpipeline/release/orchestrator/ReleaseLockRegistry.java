package com.sqlpipeline.release.orchestrator;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * 发布并发控制（§10.3）：基于 PostgreSQL advisory lock（会话级）实现 plan 级互斥。
 * 相比 JVM 内 ReentrantLock：应用崩溃时锁随会话自动释放，且天然支持多实例部署。
 * <p>
 * 注意：持锁期间占用一个平台库连接（drive 可能较久），平台池容量需预留；同一计划的
 * start/continue/retry/rerun 通过本锁串行化，不同计划之间无锁并行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReleaseLockRegistry {

    private static final long LOCK_WAIT_MS = 15_000;
    private static final long POLL_INTERVAL_MS = 100;

    private final DataSource dataSource;

    /** 在 planId 的互斥锁内执行 action；锁等待超过 15s 抛 RL 计划忙（409）。 */
    public <T> T withLock(Long planId, Supplier<T> action) {
        try (Connection conn = dataSource.getConnection()) {
            long deadline = System.currentTimeMillis() + LOCK_WAIT_MS;
            while (!tryLock(conn, planId)) {
                if (System.currentTimeMillis() > deadline) {
                    throw BizException.i18n(ErrorCode.RL_PLAN_BUSY, "error.rl.planBusy", planId);
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
            try {
                return action.get();
            } finally {
                unlock(conn, planId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.SYS_INTERNAL, "interrupted while waiting for plan lock", e);
        } catch (SQLException e) {
            throw new BizException(ErrorCode.SYS_INTERNAL, "plan lock error: " + e.getMessage(), e);
        }
    }

    private boolean tryLock(Connection conn, Long planId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, planId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private void unlock(Connection conn, Long planId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, planId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !rs.getBoolean(1)) {
                    log.warn("advisory unlock 未持有锁: planId={}", planId);
                }
            }
        } catch (SQLException e) {
            log.warn("advisory unlock 失败: planId={}, err={}", planId, e.getMessage());
        }
    }
}

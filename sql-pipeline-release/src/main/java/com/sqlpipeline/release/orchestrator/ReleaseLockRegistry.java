package com.sqlpipeline.release.orchestrator;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 发布并发控制（§10.3）：同一 plan 的 start/continue/retry/rerun 通过 JVM 内互斥锁串行化，
 * 防止并发驱动导致步骤重复执行；不同 plan 之间无锁，天然并行。一期单实例部署（ADR-07）。
 */
@Component
public class ReleaseLockRegistry {

    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock lockFor(Long planId) {
        return locks.computeIfAbsent(planId, k -> new ReentrantLock());
    }
}

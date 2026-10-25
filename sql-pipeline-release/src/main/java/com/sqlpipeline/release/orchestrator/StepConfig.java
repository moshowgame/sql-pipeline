package com.sqlpipeline.release.orchestrator;

/**
 * 单个步骤的编排配置（运行编号 / 目录名 / 目标库 / 过渡方式 / 执行者 / 提交前验证 SQL），
 * 保存于 release_plan.step_config。
 * <p>
 * verifySql（P0）：事务模式下所有语句执行完、COMMIT 之前在事务内执行的只读验证查询——
 * 返回至少一行才允许提交，否则整体回滚（用于落库结果复核，如「新表已存在/数据已写入」断言）。
 * 保存与启动时经 SqlGuard 校验仅 SELECT。
 */
public record StepConfig(String dirName, Integer stepNo, String connKey, String afterMode,
                         String executor, String verifySql) {
}

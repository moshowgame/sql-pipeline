package com.sqlpipeline.release.orchestrator;

/** 单个步骤的编排配置（运行编号 / 目录名 / 目标库 / 过渡方式 / 执行者），保存于 release_plan.step_config。 */
public record StepConfig(String dirName, Integer stepNo, String connKey, String afterMode, String executor) {
}

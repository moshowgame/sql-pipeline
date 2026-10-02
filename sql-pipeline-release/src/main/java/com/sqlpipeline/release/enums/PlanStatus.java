package com.sqlpipeline.release.enums;

/** 发布计划状态机（§7.5.3）。 */
public enum PlanStatus {
    DRAFT, RUNNING, WAITING, PAUSED, COMPLETED, FAILED, SKIPPED
}

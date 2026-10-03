package com.sqlpipeline.notify.service;

import java.util.Set;

/** 健康检查运行状态常量（避免 notify 依赖 health 模块）。 */
final class RunStatuses {

    static final String SUCCESS = "SUCCESS";
    static final Set<String> FAILED_STATUSES = Set.of("FAIL", "ERROR", "TIMEOUT");

    private RunStatuses() {
    }
}

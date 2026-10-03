package com.sqlpipeline.notify.event;

import java.util.Map;

/**
 * 告警事件。事件类型：
 * <ul>
 *   <li>HC_FAIL — 健康检查失败（FAIL/ERROR/TIMEOUT，可按连续失败次数阈值过滤）</li>
 *   <li>RELEASE_STEP_FAIL — 发布步骤失败</li>
 *   <li>PLAN_FAIL — 发布计划失败</li>
 * </ul>
 */
public record AlertEvent(String event, String severity, String title, String message,
                         Map<String, Object> properties) {

    public static final String HC_FAIL = "HC_FAIL";
    public static final String RELEASE_STEP_FAIL = "RELEASE_STEP_FAIL";
    public static final String PLAN_FAIL = "PLAN_FAIL";
}

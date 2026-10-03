package com.sqlpipeline.notify.service;

/** 告警事件类型常量（与 AlertEvent 保持一致）。 */
public final class AlertEvents {

    public static final String HC_FAIL = "HC_FAIL";
    public static final String RELEASE_STEP_FAIL = "RELEASE_STEP_FAIL";
    public static final String RELEASE_STEP_SUCCESS = "RELEASE_STEP_SUCCESS";
    public static final String PLAN_FAIL = "PLAN_FAIL";
    public static final String PLAN_COMPLETED = "PLAN_COMPLETED";
    public static final String TEST = "TEST";

    private AlertEvents() {
    }
}

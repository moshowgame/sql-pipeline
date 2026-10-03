package com.sqlpipeline.common.error;

import lombok.Getter;

/**
 * 错误码规范：{模块}{类型}{序号}，模块：DS(数据源) SG(SqlGuard) HC(健康检查) RL(发布) SYS(系统)。
 */
@Getter
public enum ErrorCode {

    SYS_INTERNAL("SYS0001", 500, "系统内部错误"),
    SYS_PARAM_INVALID("SYS0002", 400, "参数校验失败"),

    DS_NOT_FOUND("DS0001", 404, "数据源不存在"),
    DS_CONNECT_FAILED("DS0002", 502, "数据源连接失败"),
    DS_DISABLED("DS0003", 400, "数据源已禁用"),

    SG_PARSE_FAILED("SG0001", 400, "SQL 解析失败"),
    SG_NON_SELECT("SG0002", 400, "包含非 SELECT 语句"),
    SG_FORBIDDEN_CLAUSE("SG0003", 400, "包含禁用子句"),

    HC_DEF_NOT_FOUND("HC0001", 404, "健康检查定义不存在"),
    HC_CONFIG_INVALID("HC0002", 400, "断言/参数配置非法"),
    HC_CRON_INVALID("HC0003", 400, "Cron 表达式非法"),
    HC_SQL_TIMEOUT("HC0100", 504, "SQL 执行超时"),
    HC_SQL_ERROR("HC0101", 500, "SQL 执行失败"),

    RL_PLAN_NOT_FOUND("RL0001", 404, "发布计划不存在"),
    RL_PLAN_ALREADY_STARTED("RL0002", 409, "计划已启动"),
    RL_STEP_NOT_FOUND("RL0003", 404, "步骤不存在"),
    RL_STEP_NOT_RETRYABLE("RL0004", 409, "步骤不可重试（非 FAIL 状态）"),
    RL_NO_WAITING_STEP("RL0005", 409, "当前无等待中的步骤"),
    RL_NOT_EXECUTOR("RL0006", 403, "非指定执行者"),
    RL_SCAN_FAILED("RL0007", 500, "目录扫描失败"),
    RL_PLAN_BUSY("RL0008", 409, "计划正在被其他操作处理"),
    RL_STEP_EXEC_FAILED("RL0100", 500, "步骤执行失败");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(String code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }
}

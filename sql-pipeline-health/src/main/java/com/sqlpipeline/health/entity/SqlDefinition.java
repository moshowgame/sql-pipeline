package com.sqlpipeline.health.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 健康检查 SQL 定义。 */
@Data
public class SqlDefinition {

    private Long id;
    private String name;
    private String connKey;
    private String sqlText;
    /** 默认参数值，JSON 对象 {"name":value}，对应 SQL 中 ${name} 占位符。 */
    private String paramsJson;
    /** 断言目标类型：VALUE（第一行第一列的返回值）/ ROWS（返回行数），空=不断言。 */
    private String assertType;
    /** 断言操作符：== != > >= < <=。 */
    private String assertOp;
    /** 断言期望值。 */
    private BigDecimal assertValue;
    /** 兼容保留：旧版 JSON 断言配置，新数据不再写入。 */
    private String assertConfig;
    /** 绑定的告警通道（notify_channel.id）：HC_FAIL 只推送到该通道，空=不告警。 */
    private Long notifyChannelId;
    /** 列表回填：通道名称（不落库）。 */
    private String notifyChannelName;
    private String cronExpr;
    private Integer timeoutSec;
    private Integer enabled;
    private Integer version;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
}

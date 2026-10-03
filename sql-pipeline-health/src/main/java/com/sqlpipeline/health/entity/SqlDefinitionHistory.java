package com.sqlpipeline.health.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 健康检查 SQL 修改记录（版本快照，HC-5）。 */
@Data
public class SqlDefinitionHistory {

    private Long id;
    private Long sqlDefId;
    /** 被替换前的版本号（CREATE 记录初始版本）。 */
    private Integer version;
    private String sqlText;
    private String assertType;
    private String assertOp;
    private BigDecimal assertValue;
    /** 兼容保留：旧版 JSON 断言配置。 */
    private String assertConfig;
    private String cronExpr;
    private String changedBy;
    private LocalDateTime changedAt;
    private String changeType;
}

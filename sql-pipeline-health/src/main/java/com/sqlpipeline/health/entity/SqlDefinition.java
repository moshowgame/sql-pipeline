package com.sqlpipeline.health.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 健康检查 SQL 定义。 */
@Data
public class SqlDefinition {

    private Long id;
    private String name;
    private String connKey;
    private String sqlText;
    /** 默认参数值，JSON 数组，按 ? 顺序绑定。 */
    private String paramsJson;
    private String assertType;
    private String assertConfig;
    private String cronExpr;
    private Integer timeoutSec;
    private Integer enabled;
    private Integer version;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
}

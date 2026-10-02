package com.sqlpipeline.health.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 健康检查执行记录（HC-6）。 */
@Data
public class HealthCheckRun {

    private Long id;
    private Long sqlDefId;
    /** 执行时的定义版本快照。 */
    private Integer version;
    private String triggerType;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private Integer rowCount;
    private String resultHead;
    private String assertMsg;
    private String errorMsg;
    /** 分页查询时由窗口函数填充的总数，不落库。 */
    private Long totalCount;
}

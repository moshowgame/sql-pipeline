package com.sqlpipeline.release.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 发布 SQL 明细日志。 */
@Data
public class ReleaseSqlLog {

    private Long id;
    private Long planId;
    private Long stepId;
    /** 冗余步骤号：步骤被重跑删除后日志仍可按计划查询 */
    private Integer stepNo;
    private String fileName;
    /** 步骤内语句顺序（跨文件累计）。 */
    private Integer seq;
    private String sqlPreview;
    private String status;
    private Long durationMs;
    private String errorMsg;
    private LocalDateTime executedAt;
}

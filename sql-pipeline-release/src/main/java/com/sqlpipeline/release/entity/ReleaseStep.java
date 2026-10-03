package com.sqlpipeline.release.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 发布步骤：一个数字目录 = 一个事务单元。 */
@Data
public class ReleaseStep {

    private Long id;
    private Long planId;
    private Integer stepNo;
    private String dirPath;
    private String connKey;
    private String afterMode;
    private String executor;
    private String status;
    /** WAIT 步骤人工确认人/时间（continue 时记录）。 */
    private String confirmBy;
    private LocalDateTime confirmAt;
    /** 最近一次重试备注。 */
    private String retryRemark;
    /** start 时脚本内容 SHA-256（用于变更检测）。 */
    private String scriptHash;
    /** 详情接口填充：当前目录哈希与 script_hash 是否不一致（不落库）。 */
    private Boolean scriptChanged;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private Integer retryCount;
    private String errorMsg;
}

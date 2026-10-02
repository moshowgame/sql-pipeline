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
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private Integer retryCount;
    private String errorMsg;
}

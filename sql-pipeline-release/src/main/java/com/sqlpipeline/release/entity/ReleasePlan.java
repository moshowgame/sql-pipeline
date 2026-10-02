package com.sqlpipeline.release.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 发布计划。 */
@Data
public class ReleasePlan {

    private Long id;
    /** release_20261003 */
    private String planName;
    private String basePath;
    /** 未单独配置的步骤默认使用该连接。 */
    private String defaultConnKey;
    /** 各步骤编排配置 JSON：[{stepNo, connKey, afterMode, executor}]。 */
    private String stepConfig;
    private String status;
    private String crNumber;
    private String remark;
    private String operator;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer rerunCount;
    private String createdBy;
    private LocalDateTime createdAt;
}

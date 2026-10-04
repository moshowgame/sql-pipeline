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
    /** FOLDER（release_path 为目录）| ZIP（release_path 为 zip 包，解压暂未实现）。 */
    private String releaseType;
    /** 发布路径：可指定 share folder 的绝对/相对路径（相对时基于全局 base-path 解析）。 */
    private String releasePath;
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

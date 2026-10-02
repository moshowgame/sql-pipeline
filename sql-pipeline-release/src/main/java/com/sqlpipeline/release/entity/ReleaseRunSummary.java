package com.sqlpipeline.release.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 全流程重跑统计（UAT 计时，RL-9）。 */
@Data
public class ReleaseRunSummary {

    private Long id;
    private Long planId;
    private Integer runSeq;
    private Long totalMs;
    /** 各步耗时 JSON：[{stepNo, status, durationMs}]。 */
    private String stepDetail;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String operator;
}

package com.sqlpipeline.notify.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 告警推送日志（审计与排障）。 */
@Data
public class NotifyLog {

    private Long id;
    private Long channelId;
    /** 冗余通道名：通道删除后日志仍可读。 */
    private String channelName;
    private String event;
    private String title;
    /** SUCCESS | FAIL */
    private String status;
    private Integer responseCode;
    private String responseBody;
    private String errorMsg;
    private String payload;
    private LocalDateTime createdAt;
}

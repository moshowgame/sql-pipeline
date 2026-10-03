package com.sqlpipeline.notify.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 告警通道（存于平台库，配置即数据）。 */
@Data
public class NotifyChannel {

    public static final String TYPE_XMATTERS = "XMATTERS";
    public static final String AUTH_NONE = "NONE";
    public static final String AUTH_BASIC = "BASIC";
    public static final String AUTH_API_KEY = "API_KEY";

    private Long id;
    private String name;
    /** 通道类型：XMATTERS（预留扩展：DINGTALK / FEISHU / WEBHOOK 等）。 */
    private String type;
    /** xMatters 入站集成 / Webhook 触发地址。 */
    private String url;
    /** NONE | BASIC | API_KEY。 */
    private String authType;
    /** BASIC 认证用户名。 */
    private String username;
    /** API_KEY 认证的 Header 名（默认 apikey）。 */
    private String authHeaderName;
    /** BASIC 密码 / API Key 密文（AES-256-GCM）。 */
    private String secretEnc;
    /** 订阅事件 JSON 数组：HC_FAIL / RELEASE_STEP_FAIL / PLAN_FAIL。 */
    private String events;
    /** 健康检查连续失败多少次才告警（1 = 每次失败即告警）。 */
    private Integer hcFailThreshold;
    private Integer enabled;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
}

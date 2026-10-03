package com.sqlpipeline.datasource.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 数据库连接定义（目标库，仅支持 PostgreSQL）。 */
@Data
public class DbConnection {

    private Long id;
    private String connKey;
    private String displayName;
    private String jdbcUrl;
    private String username;
    private String passwordEnc;
    private String driverClass;
    /** 连接默认 Schema（空 = 驱动默认 search_path，通常为 public）；建池时经 Hikari setSchema 生效 */
    private String defaultSchema;
    private Integer poolSize;
    private Integer connTimeoutMs;
    private Integer maxRows;
    private Integer queryTimeoutS;
    private Integer enabled;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
}

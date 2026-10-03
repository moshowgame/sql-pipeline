package com.sqlpipeline.web.dto;

import com.sqlpipeline.datasource.entity.DbConnection;

import java.time.LocalDateTime;

/** 连接视图：不返回明文密码，仅掩码。 */
public record ConnectionView(Long id, String connKey, String displayName, String jdbcUrl, String username,
                             String passwordMask, String driverClass, String defaultSchema, Integer poolSize,
                             Integer connTimeoutMs, Integer maxRows, Integer queryTimeoutS, Integer enabled,
                             String createdBy, LocalDateTime createdAt, String updatedBy, LocalDateTime updatedAt) {

    private static final String MASK = "******";

    public static ConnectionView from(DbConnection e) {
        return new ConnectionView(e.getId(), e.getConnKey(), e.getDisplayName(), e.getJdbcUrl(),
                e.getUsername(), MASK, e.getDriverClass(), e.getDefaultSchema(), e.getPoolSize(),
                e.getConnTimeoutMs(), e.getMaxRows(), e.getQueryTimeoutS(), e.getEnabled(),
                e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedBy(), e.getUpdatedAt());
    }
}

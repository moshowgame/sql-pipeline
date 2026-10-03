package com.sqlpipeline.web.dto;

/** 更新连接请求：connKey 不可改，password 留空表示保持不变。 */
public record ConnectionUpdateReq(
        String displayName,
        String jdbcUrl,
        String username,
        String password,
        String driverClass,
        String defaultSchema,
        Integer poolSize,
        Integer connTimeoutMs,
        Integer maxRows,
        Integer queryTimeoutS,
        Integer enabled) {
}

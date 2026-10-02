package com.sqlpipeline.release.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "sql-pipeline.release")
public class ReleaseProperties {

    /** 发布目录根路径：所有发布计划（release_YYYYMMDD 目录）的父目录。 */
    private String basePath = "./data/releases";

    /** SQL 文件后缀。 */
    private String sqlSuffix = ".sql";

    /** 单计划最大步骤数。 */
    private int maxSteps = 99;

    /** 单条 SQL 语句超时秒数。 */
    private int statementTimeoutSec = 60;
}

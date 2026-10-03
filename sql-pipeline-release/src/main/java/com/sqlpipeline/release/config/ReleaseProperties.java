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

    /** 单步骤总超时秒数（0 = 不限制）；语句之间检查，超时后回滚并置 FAIL。 */
    private int stepTotalTimeoutSec = 3600;

    /** 异步驱动线程池大小（drive 与 retry 在池内执行，HTTP 立即返回）。 */
    private int driverPoolSize = 2;

    /** 异步驱动队列容量；满时由调用线程执行（退化为同步）。 */
    private int driverQueueCapacity = 200;
}

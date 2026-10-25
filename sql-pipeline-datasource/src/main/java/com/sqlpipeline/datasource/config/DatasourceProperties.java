package com.sqlpipeline.datasource.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 数据源全局配置（P0-池容量预算 / P0-流式读取）。
 */
@Data
@ConfigurationProperties(prefix = "sql-pipeline.datasource")
public class DatasourceProperties {

    /**
     * 连接池总容量预算：同一目标库（按规范化 jdbc_url 归组）所有启用连接的 pool_size 之和不得超过该值。
     * 超限报 DS0004；多实例部署时建议改用 PgBouncer 并下调各池容量。
     */
    private int poolBudgetPerUrl = 20;

    /**
     * JDBC 流式读取的 fetchSize：autoCommit=false + fetchSize>0 时 pgjdbc 按游标分批拉取，
     * 避免大结果集整包载入内存；实际取 min(fetchSize, maxRows+1)。
     */
    private int fetchSize = 500;

    /** 新建连接未显式指定 pool_size 时的默认值（P0 下调：10 → 8）。 */
    private int defaultPoolSize = 8;
}

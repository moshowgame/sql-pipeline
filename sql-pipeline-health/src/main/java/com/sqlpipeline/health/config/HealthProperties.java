package com.sqlpipeline.health.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "sql-pipeline.health")
public class HealthProperties {

    private Scheduler scheduler = new Scheduler();

    private Executor executor = new Executor();

    private int defaultTimeoutSec = 30;

    private int defaultMaxRows = 1000;

    @Data
    public static class Scheduler {
        /** Spring Boot 默认 TaskScheduler 池大小为 1，一个慢任务会阻塞所有健康检查，必须显式设置。 */
        private int poolSize = 8;
    }

    @Data
    public static class Executor {
        /** 检查执行线程池：调度线程只负责触发，慢查询在独立池内执行，不挤占调度触发。 */
        private int poolSize = 8;
    }
}

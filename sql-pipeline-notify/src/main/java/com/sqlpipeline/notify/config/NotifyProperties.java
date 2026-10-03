package com.sqlpipeline.notify.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "sql-pipeline.notify")
public class NotifyProperties {

    /** 告警总开关（通道自身的 enabled 仍独立生效）。 */
    private boolean enabled = true;

    /** 单次 HTTP 推送超时毫秒。 */
    private int timeoutMs = 10_000;

    /** 推送线程池大小。 */
    private int poolSize = 2;

    /** 推送队列容量，满后由调用线程执行（不丢事件）。 */
    private int queueCapacity = 1000;
}

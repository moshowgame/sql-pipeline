package com.sqlpipeline.notify.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 告警推送线程池：与业务线程隔离，推送失败绝不影响业务流程。 */
@Configuration
public class NotifyConfig {

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor alertExecutor(NotifyProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("alert-push-");
        executor.setCorePoolSize(properties.getPoolSize());
        executor.setMaxPoolSize(properties.getPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        // 队列满时由调用线程执行，宁可短暂阻塞也不丢告警
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}

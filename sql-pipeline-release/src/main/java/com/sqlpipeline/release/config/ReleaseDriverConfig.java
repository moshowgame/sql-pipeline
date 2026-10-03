package com.sqlpipeline.release.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 发布驱动线程池：start/continue/retry/rerun 的实际执行（drive/runStep）在此池内进行，
 * HTTP 请求仅完成校验与元数据写入后立即返回；进度经 SSE 推送。
 */
@Configuration
@RequiredArgsConstructor
public class ReleaseDriverConfig {

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor releaseDriverExecutor(ReleaseProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("release-driver-");
        executor.setCorePoolSize(properties.getDriverPoolSize());
        executor.setMaxPoolSize(properties.getDriverPoolSize());
        executor.setQueueCapacity(properties.getDriverQueueCapacity());
        // 队列满时由调用线程执行，宁可退化为同步也不丢任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        return executor;
    }
}

package com.sqlpipeline.health.scheduler;

import com.sqlpipeline.health.config.HealthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 健康检查调度线程池 + 执行线程池。
 * 关键坑 1：Spring Boot 默认 TaskScheduler 池大小为 1，一个慢任务会阻塞所有健康检查，
 * 因此必须显式设置 poolSize（默认 8，可配置）。
 * 关键坑 2：调度线程只负责触发，实际查询在独立执行池（hc-exec-）内运行，
 * 避免一波慢检查挤占调度池、推迟其他检查的触发时刻。
 */
@Configuration
@RequiredArgsConstructor
public class SchedulerConfig implements SchedulingConfigurer {

    private final HealthProperties properties;

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler healthCheckTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(properties.getScheduler().getPoolSize());
        scheduler.setThreadNamePrefix("hc-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor healthCheckExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getExecutor().getPoolSize());
        executor.setMaxPoolSize(properties.getExecutor().getPoolSize());
        executor.setThreadNamePrefix("hc-exec-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(healthCheckTaskScheduler());
    }
}

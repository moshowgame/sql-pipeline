package com.sqlpipeline.health.scheduler;

import com.sqlpipeline.health.config.HealthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 健康检查调度线程池。
 * 关键坑：Spring Boot 默认 TaskScheduler 池大小为 1，一个慢任务会阻塞所有健康检查，
 * 因此必须显式设置 poolSize（默认 8，可配置）。
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

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(healthCheckTaskScheduler());
    }
}

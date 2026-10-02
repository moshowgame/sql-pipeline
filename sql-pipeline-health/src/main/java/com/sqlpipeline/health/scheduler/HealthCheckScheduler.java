package com.sqlpipeline.health.scheduler;

import com.sqlpipeline.health.entity.SqlDefinition;
import com.sqlpipeline.health.enums.TriggerType;
import com.sqlpipeline.health.mapper.SqlDefinitionMapper;
import com.sqlpipeline.health.service.HealthCheckService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import static org.springframework.util.StringUtils.hasText;

/**
 * 健康检查动态调度：运行期注册 / 取消 Cron，支持修改后即时生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HealthCheckScheduler {

    private final ThreadPoolTaskScheduler taskScheduler;
    private final HealthCheckService healthCheckService;
    private final SqlDefinitionMapper defMapper;
    private final Map<Long, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    @PostConstruct
    public void loadAll() {
        for (SqlDefinition def : defMapper.selectEnabled()) {
            try {
                reschedule(def);
            } catch (Exception e) {
                log.warn("健康检查调度注册失败: id={}, cron={}", def.getId(), def.getCronExpr(), e);
            }
        }
        log.info("健康检查调度初始化完成: {} 个定时任务", futures.size());
    }

    /** 重注册（先取消旧任务）；enabled 且有 cron 才注册。 */
    public void reschedule(SqlDefinition def) {
        cancel(def.getId());
        if (def.getEnabled() != null && def.getEnabled() == 1 && hasText(def.getCronExpr())) {
            CronTrigger trigger = new CronTrigger(def.getCronExpr());
            ScheduledFuture<?> future = taskScheduler.schedule(
                    () -> executeQuietly(def.getId()), trigger);
            futures.put(def.getId(), future);
            log.info("健康检查调度已注册: id={}, cron={}", def.getId(), def.getCronExpr());
        }
    }

    /** cancel(false)：让正在执行的本次检查跑完。 */
    public void cancel(Long defId) {
        ScheduledFuture<?> future = futures.remove(defId);
        if (future != null) {
            future.cancel(false);
            log.info("健康检查调度已取消: id={}", defId);
        }
    }

    private void executeQuietly(Long defId) {
        try {
            healthCheckService.run(defId, TriggerType.CRON.name(), null);
        } catch (Exception e) {
            log.warn("定时健康检查失败: id={}, err={}", defId, e.getMessage());
        }
    }
}

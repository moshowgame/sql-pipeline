package com.sqlpipeline.health.service;

import com.sqlpipeline.common.api.PageResult;
import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.common.util.JsonUtils;
import com.sqlpipeline.datasource.entity.DbConnection;
import com.sqlpipeline.datasource.executor.DynamicSqlExecutor;
import com.sqlpipeline.datasource.executor.QueryResult;
import com.sqlpipeline.datasource.guard.SqlGuard;
import com.sqlpipeline.datasource.mapper.DbConnectionMapper;
import com.sqlpipeline.health.assertion.AssertConfig;
import com.sqlpipeline.health.assertion.AssertEvaluator;
import com.sqlpipeline.health.assertion.AssertResult;
import com.sqlpipeline.health.config.HealthProperties;
import com.sqlpipeline.health.entity.HealthCheckRun;
import com.sqlpipeline.health.entity.SqlDefinition;
import com.sqlpipeline.health.entity.SqlDefinitionHistory;
import com.sqlpipeline.health.enums.RunStatus;
import com.sqlpipeline.health.mapper.HealthCheckRunMapper;
import com.sqlpipeline.health.mapper.SqlDefinitionHistoryMapper;
import com.sqlpipeline.health.mapper.SqlDefinitionMapper;
import com.sqlpipeline.health.scheduler.HealthCheckScheduler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.util.StringUtils.hasText;

/**
 * 健康检查服务：定义 CRUD（含版本快照）、执行（L2 拦截 + 断言 + 执行记录）、调度联动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthCheckService {

    private static final int RESULT_HEAD_ROWS = 20;
    private static final int RESULT_HEAD_MAX_CHARS = 2000;

    private final SqlDefinitionMapper defMapper;
    private final SqlDefinitionHistoryMapper historyMapper;
    private final HealthCheckRunMapper runMapper;
    private final DbConnectionMapper connectionMapper;
    private final SqlGuard sqlGuard;
    private final DynamicSqlExecutor executor;
    private final AssertEvaluator evaluator;
    private final HealthProperties properties;
    // 通过 ObjectProvider 延迟获取，打破 scheduler ↔ service 的构造期循环依赖
    private final ObjectProvider<HealthCheckScheduler> schedulerProvider;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    // ---------- 定义管理 ----------

    public Long create(SqlDefinition def, String operator) {
        validate(def, true);
        def.setVersion(1);
        def.setCreatedBy(operator);
        def.setUpdatedBy(operator);
        defMapper.insert(def);
        saveHistory(def, def.getVersion(), "CREATE", operator);
        reschedule(def);
        log.info("健康检查已创建: id={}, name={}, cron={}", def.getId(), def.getName(), def.getCronExpr());
        return def.getId();
    }

    /** 更新写入历史快照并递增版本（HC-5）。 */
    public void update(SqlDefinition def, String operator) {
        SqlDefinition old = requireExists(def.getId());
        validate(def, false);

        saveHistory(old, old.getVersion(), "UPDATE", operator);

        def.setVersion(old.getVersion() + 1);
        def.setUpdatedBy(operator);
        defMapper.update(def);
        reschedule(def);
        log.info("健康检查已更新: id={}, version={}", def.getId(), def.getVersion());
    }

    public void enable(Long id, String operator) {
        requireExists(id);
        defMapper.updateEnabled(id, 1, operator);
        reschedule(defMapper.selectById(id));
    }

    public void disable(Long id, String operator) {
        SqlDefinition old = requireExists(id);
        saveHistory(old, old.getVersion(), "DISABLE", operator);
        defMapper.updateEnabled(id, 0, operator);
        schedulerProvider.ifAvailable(s -> s.cancel(id));
    }

    public SqlDefinition get(Long id) {
        return requireExists(id);
    }

    public List<SqlDefinition> list() {
        return defMapper.selectAll();
    }

    // ---------- 执行 ----------

    public HealthCheckRun run(Long defId, String triggerType, List<Object> paramOverride) {
        SqlDefinition def = requireExists(defId);
        DbConnection conn = connectionMapper.selectByKey(def.getConnKey());
        if (conn == null) {
            throw new BizException(ErrorCode.DS_NOT_FOUND, "连接不存在: " + def.getConnKey());
        }
        if (conn.getEnabled() == null || conn.getEnabled() != 1) {
            throw new BizException(ErrorCode.DS_DISABLED, "连接已禁用: " + def.getConnKey());
        }
        // L2 拦截：防历史脏数据、防绕过 API 直改库
        sqlGuard.assertSelectOnly(def.getSqlText());

        long t0 = System.currentTimeMillis();
        HealthCheckRun run = new HealthCheckRun();
        run.setSqlDefId(defId);
        run.setVersion(def.getVersion());
        run.setTriggerType(triggerType);
        run.setStatus("RUNNING");
        run.setStartedAt(LocalDateTime.now());
        runMapper.insert(run);

        try {
            List<Object> params = paramOverride != null
                    ? paramOverride
                    : JsonUtils.parseList(def.getParamsJson(), ErrorCode.HC_CONFIG_INVALID);
            int maxRows = conn.getMaxRows() != null ? conn.getMaxRows() : properties.getDefaultMaxRows();
            QueryResult qr = executor.query(def.getConnKey(), def.getSqlText(),
                    params, maxRows, def.getTimeoutSec());

            String status = RunStatus.SUCCESS.name();
            String assertMsg = null;
            if (hasText(def.getAssertType())) {
                AssertConfig cfg = JsonUtils.fromJson(def.getAssertConfig(), AssertConfig.class,
                        ErrorCode.HC_CONFIG_INVALID);
                if (cfg == null) {
                    throw new BizException(ErrorCode.HC_CONFIG_INVALID, "断言配置为空");
                }
                cfg.validate();
                AssertResult ar = evaluator.evaluate(cfg, qr);
                status = ar.isPass() ? RunStatus.SUCCESS.name() : RunStatus.FAIL.name();
                assertMsg = JsonUtils.truncate(ar.getMessage(), 1000);
            }
            run.setStatus(status);
            run.setRowCount(qr.getRowCount());
            run.setResultHead(JsonUtils.truncate(qr.toJson(RESULT_HEAD_ROWS), RESULT_HEAD_MAX_CHARS));
            run.setAssertMsg(assertMsg);
        } catch (BizException e) {
            run.setStatus(e.getErrorCode() == ErrorCode.HC_SQL_TIMEOUT
                    ? RunStatus.TIMEOUT.name() : RunStatus.ERROR.name());
            run.setErrorMsg(JsonUtils.truncate(e.getMessage(), 2000));
        } catch (Exception e) {
            run.setStatus(RunStatus.ERROR.name());
            run.setErrorMsg(JsonUtils.truncate(e.getMessage(), 2000));
        } finally {
            run.setFinishedAt(LocalDateTime.now());
            run.setDurationMs(System.currentTimeMillis() - t0);
            runMapper.updateResult(run);
            recordMetrics(defId, run.getStatus(), run.getDurationMs());
        }
        log.info("健康检查执行完成: defId={}, version={}, status={}, durationMs={}",
                defId, run.getVersion(), run.getStatus(), run.getDurationMs());
        return run;
    }

    public PageResult<HealthCheckRun> pageRuns(Long defId, int page, int size) {
        requireExists(defId);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        List<HealthCheckRun> items = runMapper.pageByDefId(defId, safeSize, (long) (safePage - 1) * safeSize);
        long total = items.isEmpty() ? 0 : items.get(0).getTotalCount();
        return PageResult.of(total, safePage, safeSize, items);
    }

    public List<SqlDefinitionHistory> history(Long defId) {
        requireExists(defId);
        return historyMapper.selectByDefId(defId);
    }

    // ---------- 内部 ----------

    private void validate(SqlDefinition def, boolean isCreate) {
        if (connectionMapper.selectByKey(def.getConnKey()) == null) {
            throw new BizException(ErrorCode.DS_NOT_FOUND, "连接不存在: " + def.getConnKey());
        }
        // L1 拦截：脏数据不入库
        sqlGuard.assertSelectOnly(def.getSqlText());
        if (hasText(def.getParamsJson())) {
            JsonUtils.parseList(def.getParamsJson(), ErrorCode.HC_CONFIG_INVALID);
        }
        if (hasText(def.getAssertType())) {
            AssertConfig cfg = JsonUtils.fromJson(def.getAssertConfig(), AssertConfig.class,
                    ErrorCode.HC_CONFIG_INVALID);
            if (cfg == null) {
                throw new BizException(ErrorCode.HC_CONFIG_INVALID, "断言配置为空");
            }
            cfg.validate();
        } else {
            def.setAssertType(null);
            def.setAssertConfig(null);
        }
        if (hasText(def.getCronExpr())) {
            try {
                new CronTrigger(def.getCronExpr());
            } catch (IllegalArgumentException e) {
                throw new BizException(ErrorCode.HC_CRON_INVALID,
                        "Cron 表达式非法（Spring 6 段格式）: " + e.getMessage());
            }
        }
        if (def.getTimeoutSec() == null) {
            def.setTimeoutSec(properties.getDefaultTimeoutSec());
        }
        if (def.getEnabled() == null) {
            def.setEnabled(1);
        }
        if (!isCreate) {
            requireExists(def.getId());
        }
    }

    private void saveHistory(SqlDefinition content, int version, String changeType, String operator) {
        SqlDefinitionHistory h = new SqlDefinitionHistory();
        h.setSqlDefId(content.getId());
        h.setVersion(version);
        h.setSqlText(content.getSqlText());
        h.setAssertType(content.getAssertType());
        h.setAssertConfig(content.getAssertConfig());
        h.setCronExpr(content.getCronExpr());
        h.setChangeType(changeType);
        h.setChangedBy(operator);
        historyMapper.insert(h);
    }

    private void reschedule(SqlDefinition def) {
        schedulerProvider.ifAvailable(s -> s.reschedule(def));
    }

    private SqlDefinition requireExists(Long id) {
        SqlDefinition def = defMapper.selectById(id);
        if (def == null) {
            throw new BizException(ErrorCode.HC_DEF_NOT_FOUND, "健康检查定义不存在: " + id);
        }
        return def;
    }

    private void recordMetrics(Long defId, String status, long durationMs) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry == null) {
            return;
        }
        try {
            Counter.builder("hc_exec_total")
                    .tag("def_id", String.valueOf(defId)).tag("status", status)
                    .register(registry).increment();
            DistributionSummary.builder("hc_exec_duration_ms")
                    .tag("def_id", String.valueOf(defId))
                    .register(registry).record(durationMs);
        } catch (Exception e) {
            log.debug("指标记录失败: {}", e.getMessage());
        }
    }
}

package com.sqlpipeline.release.orchestrator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.common.util.JsonUtils;
import com.sqlpipeline.datasource.entity.DbConnection;
import com.sqlpipeline.datasource.mapper.DbConnectionMapper;
import com.sqlpipeline.datasource.registry.DataSourceRegistry;
import com.sqlpipeline.release.config.ReleaseProperties;
import com.sqlpipeline.release.entity.ReleasePlan;
import com.sqlpipeline.release.entity.ReleaseRunSummary;
import com.sqlpipeline.release.entity.ReleaseSqlLog;
import com.sqlpipeline.release.entity.ReleaseStep;
import com.sqlpipeline.release.enums.AfterMode;
import com.sqlpipeline.release.enums.PlanStatus;
import com.sqlpipeline.release.enums.StepStatus;
import com.sqlpipeline.release.event.ReleaseEventPublisher;
import com.sqlpipeline.release.mapper.ReleasePlanMapper;
import com.sqlpipeline.release.mapper.ReleaseRunSummaryMapper;
import com.sqlpipeline.release.mapper.ReleaseSqlLogMapper;
import com.sqlpipeline.release.mapper.ReleaseStepMapper;
import com.sqlpipeline.release.scanner.ReleaseScanner;
import com.sqlpipeline.release.scanner.ScannedStep;
import com.sqlpipeline.release.scanner.ScannedStepView;
import com.sqlpipeline.datasource.guard.SqlSplitter;
import com.sqlpipeline.notify.service.AlertService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.springframework.util.StringUtils.hasText;

/**
 * 发布编排引擎：显式状态机 + plan 级互斥锁（ADR-04/07），不引入流程引擎。
 * 步骤即事务边界：一个数字目录 = 一个事务，失败整目录回滚（ADR-05）。
 * <p>
 * 注意：所有会推进状态机的入口（start/continue/retry/rerun）必须先获取 planId 锁，
 * driveInternal 仅允许在持锁状态下调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseOrchestrator {

    private static final Pattern PLAN_NAME = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final TypeReference<List<StepConfig>> STEP_CONFIG_LIST = new TypeReference<>() {
    };

    private final ReleaseScanner scanner;
    private final ReleasePlanMapper planMapper;
    private final ReleaseStepMapper stepMapper;
    private final ReleaseSqlLogMapper sqlLogMapper;
    private final ReleaseRunSummaryMapper summaryMapper;
    private final DbConnectionMapper connectionMapper;
    private final DataSourceRegistry registry;
    private final ReleaseLockRegistry lockRegistry;
    private final ReleaseEventPublisher publisher;
    private final ReleaseProperties properties;
    /** 仅用于 start 时"步骤落库 + 计划置 RUNNING"的元数据事务；步骤执行事务走目标库 JDBC，绝不共用 */
    private final TransactionTemplate transactionTemplate;
    private final AlertService alertService;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    // ---------- 创建与预览 ----------

    public ReleasePlan create(String planName, String defaultConnKey, List<StepConfig> stepConfigs, String operator) {
        Path base = requireBaseDir();
        validateConnKey(defaultConnKey);
        for (StepConfig c : stepConfigs == null ? List.<StepConfig>of() : stepConfigs) {
            if (c.stepNo() == null || c.stepNo() < 1) {
                throw new BizException(ErrorCode.SYS_PARAM_INVALID, "步骤配置缺少合法 stepNo: " + c);
            }
            validateConnKey(c.connKey());
            if (hasText(c.afterMode()) && !AfterMode.CONTINUE.name().equalsIgnoreCase(c.afterMode())
                    && !AfterMode.WAIT.name().equalsIgnoreCase(c.afterMode())) {
                throw new BizException(ErrorCode.SYS_PARAM_INVALID,
                        "afterMode 只允许 CONTINUE/WAIT: " + c.afterMode());
            }
        }
        ReleasePlan plan = new ReleasePlan();
        plan.setPlanName(planName);
        plan.setBasePath(base.toString());
        plan.setDefaultConnKey(defaultConnKey);
        plan.setStepConfig(JsonUtils.toJson(stepConfigs == null ? List.of() : stepConfigs));
        plan.setStatus(PlanStatus.DRAFT.name());
        plan.setCreatedBy(operator);
        planMapper.insert(plan);
        log.info("发布计划已创建: id={}, planName={}, createdBy={}", plan.getId(), planName, operator);
        return plan;
    }

    /** 预览目录结构与步骤配置合并结果（不落库）。 */
    public List<ScannedStepView> preview(String planName, String defaultConnKey, List<StepConfig> stepConfigs) {
        Path base = requireBaseDir();
        Path planDir = resolvePlanDir(base.toString(), planName);
        Map<Integer, StepConfig> cfgMap = indexConfigs(stepConfigs);
        return scanner.scan(planDir).stream()
                .map(s -> {
                    StepConfig c = cfgMap.get(s.no());
                    return new ScannedStepView(s.no(), s.dir().getFileName().toString(),
                            s.sqlFiles().stream().map(p -> p.getFileName().toString()).toList(),
                            c != null && hasText(c.connKey()) ? c.connKey() : defaultConnKey,
                            c == null || !hasText(c.afterMode()) ? AfterMode.CONTINUE.name()
                                    : c.afterMode().toUpperCase(),
                            c == null ? null : c.executor());
                })
                .toList();
    }

    // ---------- 状态机操作 ----------

    /** 执行者提交 CR + Remark 后启动（RL-5）。 */
    public void start(Long planId, String crNumber, String remark, String operator) {
        ReentrantLock lock = lockRegistry.lockFor(planId);
        lock.lock();
        try {
            startInternal(planId, crNumber, remark, operator);
        } finally {
            lock.unlock();
        }
    }

    /** 手工继续（RL-6）。 */
    public void continueNext(Long planId, String operator) {
        ReentrantLock lock = lockRegistry.lockFor(planId);
        lock.lock();
        try {
            ReleasePlan plan = requirePlan(planId);
            if (!PlanStatus.WAITING.name().equals(plan.getStatus())) {
                throw new BizException(ErrorCode.RL_NO_WAITING_STEP,
                        "计划当前状态为 " + plan.getStatus() + "，仅 WAITING 可继续");
            }
            ReleaseStep waiting = stepMapper.selectWaiting(planId);
            if (waiting != null) {
                assertExecutor(waiting, operator);
                stepMapper.updateStatus(waiting.getId(), StepStatus.SUCCESS.name());
                publishStep(waiting, StepStatus.SUCCESS.name());
                log.info("WAIT 步骤人工确认通过: planId={}, stepNo={}, operator={}",
                        planId, waiting.getStepNo(), operator);
            } else {
                // retryStep 成功后计划处于 WAITING 但无等待步骤：校验下一步执行者后推进
                ReleaseStep next = stepMapper.selectNextActionable(planId);
                if (next == null) {
                    planMapper.updateStatus(planId, PlanStatus.RUNNING.name());
                    finishPlan(planId);
                    return;
                }
                assertExecutor(next, operator);
            }
            planMapper.updateStatus(planId, PlanStatus.RUNNING.name());
            driveInternal(planId);
        } finally {
            lock.unlock();
        }
    }

    /** 单步重试：仅重跑该步，不触发 after_mode 编排（RL-10 / ADR-06）。 */
    public void retryStep(Long planId, int stepNo, String operator) {
        ReentrantLock lock = lockRegistry.lockFor(planId);
        lock.lock();
        try {
            requirePlan(planId);
            ReleaseStep step = stepMapper.selectByNo(planId, stepNo);
            if (step == null) {
                throw new BizException(ErrorCode.RL_STEP_NOT_FOUND, "步骤不存在: " + stepNo);
            }
            if (!StepStatus.FAIL.name().equals(step.getStatus())) {
                throw new BizException(ErrorCode.RL_STEP_NOT_RETRYABLE,
                        "步骤 " + stepNo + " 状态为 " + step.getStatus() + "，仅 FAIL 可重试");
            }
            assertExecutor(step, operator);
            stepMapper.incrementRetry(step.getId());
            runStep(step);
            ReleaseStep after = stepMapper.selectById(step.getId());
            if (StepStatus.SUCCESS.name().equals(after.getStatus())) {
                // 重试成功 → 计划置 WAITING，由人工决定是否继续，不自动推进
                planMapper.updateStatus(planId, PlanStatus.WAITING.name());
                publisher.sendPlan(planId, Map.of("planId", planId, "status", PlanStatus.WAITING.name()));
            }
        } finally {
            lock.unlock();
        }
    }

    /** 全流程重跑（RL-9）：记录上次运行摘要后清理步骤重新开始。 */
    public void rerunAll(Long planId, String operator) {
        ReentrantLock lock = lockRegistry.lockFor(planId);
        lock.lock();
        try {
            ReleasePlan plan = requirePlan(planId);
            if (PlanStatus.RUNNING.name().equals(plan.getStatus())) {
                throw new BizException(ErrorCode.RL_PLAN_ALREADY_STARTED, "计划运行中，无法重跑");
            }
            recordSummary(plan, plan.getRerunCount() + 1);
            stepMapper.deleteByPlan(planId);
            planMapper.resetForRerun(planId);
            log.info("发布计划全流程重跑: planId={}, runSeq={}, operator={}",
                    planId, plan.getRerunCount() + 1, operator);
            startInternal(planId, plan.getCrNumber(), "UAT rerun by " + operator, operator);
        } finally {
            lock.unlock();
        }
    }

    // ---------- 启动恢复 ----------

    /**
     * 崩溃恢复：应用重启后
     * 1) 卡在 RUNNING 的步骤 → FAIL（执行被中断，可人工重试）；
     * 2) 仍处于 RUNNING 的计划：有 FAIL 步骤 → FAILED；仍有待执行步骤 → WAITING（人工 continue 恢复推进）；
     *    全部步骤已终态 → 补记 COMPLETED。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        try {
            List<ReleaseStep> running = stepMapper.selectRunning();
            for (ReleaseStep step : running) {
                stepMapper.markFail(step.getId(), null, "应用重启，执行被中断");
                log.warn("启动恢复: 步骤置 FAIL, planId={}, stepNo={}", step.getPlanId(), step.getStepNo());
            }
            for (ReleasePlan plan : planMapper.selectAll()) {
                if (!PlanStatus.RUNNING.name().equals(plan.getStatus())) {
                    continue;
                }
                Long planId = plan.getId();
                if (stepMapper.countByStatus(planId, StepStatus.RUNNING.name()) > 0) {
                    continue;
                }
                if (stepMapper.countByStatus(planId, StepStatus.FAIL.name()) > 0) {
                    planMapper.updateStatus(planId, PlanStatus.FAILED.name());
                    log.warn("启动恢复: 计划置 FAILED, planId={}", planId);
                } else if (stepMapper.countByStatus(planId, StepStatus.PENDING.name()) > 0
                        || stepMapper.countByStatus(planId, StepStatus.WAITING_CONTINUE.name()) > 0) {
                    planMapper.updateStatus(planId, PlanStatus.WAITING.name());
                    log.warn("启动恢复: 计划置 WAITING（等待人工 continue）, planId={}", planId);
                } else {
                    planMapper.markFinished(planId, PlanStatus.COMPLETED.name());
                    recordSummary(planMapper.selectById(planId), plan.getRerunCount() + 1);
                    log.warn("启动恢复: 计划补记 COMPLETED, planId={}", planId);
                }
            }
        } catch (Exception e) {
            log.error("启动恢复失败", e);
        }
    }

    // ---------- 查询 ----------

    public ReleasePlan getPlan(Long planId) {
        return requirePlan(planId);
    }

    public List<ReleasePlan> listPlans() {
        return planMapper.selectAll();
    }

    public List<ReleaseStep> listSteps(Long planId) {
        requirePlan(planId);
        return stepMapper.selectByPlan(planId);
    }

    public List<StepConfig> parseStepConfigs(String json) {
        List<StepConfig> configs = JsonUtils.fromJson(json, STEP_CONFIG_LIST, ErrorCode.SYS_INTERNAL);
        return configs == null ? List.of() : configs;
    }

    public List<ReleaseSqlLog> logs(Long planId, Integer stepNo) {
        requirePlan(planId);
        return sqlLogMapper.selectByPlan(planId, stepNo);
    }

    public List<ReleaseRunSummary> summaries(Long planId) {
        requirePlan(planId);
        return summaryMapper.selectByPlan(planId);
    }

    // ---------- 内部：状态机驱动（必须持锁） ----------

    private void startInternal(Long planId, String crNumber, String remark, String operator) {
        ReleasePlan plan = requirePlan(planId);
        if (!PlanStatus.DRAFT.name().equals(plan.getStatus())) {
            throw new BizException(ErrorCode.RL_PLAN_ALREADY_STARTED,
                    "计划当前状态为 " + plan.getStatus() + "，仅 DRAFT 可启动");
        }
        List<ScannedStep> scanned = scanner.scan(resolvePlanDir(plan.getBasePath(), plan.getPlanName()));
        if (scanned.isEmpty()) {
            // 整个 plan 目录不存在 / 无有效步骤 → SKIPPED（RL-8）
            planMapper.markFinished(planId, PlanStatus.SKIPPED.name());
            publisher.sendPlan(planId, Map.of("planId", planId, "status", PlanStatus.SKIPPED.name()));
            log.info("发布计划目录不存在或无 SQL，标记 SKIPPED: planId={}, planName={}", planId, plan.getPlanName());
            return;
        }
        Map<Integer, StepConfig> cfgs = indexConfigs(parseStepConfigs(plan.getStepConfig()));
        List<ReleaseStep> steps = new ArrayList<>();
        for (ScannedStep s : scanned) {
            StepConfig c = cfgs.get(s.no());
            String connKey = c != null && hasText(c.connKey()) ? c.connKey() : plan.getDefaultConnKey();
            if (!hasText(connKey)) {
                throw new BizException(ErrorCode.SYS_PARAM_INVALID, "步骤 " + s.no() + " 未配置目标库(connKey)");
            }
            validateConnKey(connKey);
            ReleaseStep step = new ReleaseStep();
            step.setPlanId(planId);
            step.setStepNo(s.no());
            step.setDirPath(s.dir().toAbsolutePath().toString());
            step.setConnKey(connKey);
            step.setAfterMode(c != null && hasText(c.afterMode())
                    ? c.afterMode().toUpperCase() : AfterMode.CONTINUE.name());
            step.setExecutor(c == null ? null : c.executor());
            step.setStatus(StepStatus.PENDING.name());
            steps.add(step);
        }
        // 启动即校验第一步执行者（后续步骤在 continue/retry 时校验）
        assertExecutor(steps.get(0), operator);

        // 元数据写入保持原子：避免"步骤已落库但计划仍 DRAFT"的中间态
        transactionTemplate.executeWithoutResult(tx -> {
            stepMapper.batchInsert(steps);
            planMapper.updateForStart(planId, crNumber, remark, operator, PlanStatus.RUNNING.name());
        });
        publisher.sendPlan(planId, Map.of("planId", planId, "status", PlanStatus.RUNNING.name()));
        log.info("发布计划启动: planId={}, planName={}, steps={}, operator={}, cr={}",
                planId, plan.getPlanName(), steps.size(), operator, crNumber);
        driveInternal(planId);
    }

    /**
     * 驱动引擎：从当前可推进的步骤开始，直到遇到 WAIT / 失败 / 完成。
     * 调用方必须已持有 planId 的锁。
     */
    private void driveInternal(Long planId) {
        while (true) {
            ReleaseStep next = stepMapper.selectNextActionable(planId);
            if (next == null) {
                finishPlan(planId);
                return;
            }
            if (StepStatus.WAITING_CONTINUE.name().equals(next.getStatus())) {
                planMapper.updateStatus(planId, PlanStatus.WAITING.name());
                publisher.sendPlan(planId, Map.of("planId", planId, "status", PlanStatus.WAITING.name(),
                        "waitingStep", next.getStepNo()));
                return;
            }
            runStep(next);
            ReleaseStep done = stepMapper.selectById(next.getId());
            if (StepStatus.FAIL.name().equals(done.getStatus())) {
                planMapper.updateStatus(planId, PlanStatus.FAILED.name());
                publisher.sendPlan(planId, Map.of("planId", planId, "status", PlanStatus.FAILED.name(),
                        "failStep", done.getStepNo()));
                notifyPlanFail(planId);
                return;
            }
            if (StepStatus.SUCCESS.name().equals(done.getStatus())
                    && AfterMode.WAIT.name().equalsIgnoreCase(done.getAfterMode())) {
                stepMapper.updateStatus(done.getId(), StepStatus.WAITING_CONTINUE.name());
                publishStep(done, StepStatus.WAITING_CONTINUE.name());
                planMapper.updateStatus(planId, PlanStatus.WAITING.name());
                publisher.sendPlan(planId, Map.of("planId", planId, "status", PlanStatus.WAITING.name(),
                        "waitingStep", done.getStepNo()));
                return;
            }
            // SUCCESS+CONTINUE / SKIPPED → 继续下一步
        }
    }

    private void finishPlan(Long planId) {
        int fails = stepMapper.countByStatus(planId, StepStatus.FAIL.name());
        if (fails > 0) {
            planMapper.updateStatus(planId, PlanStatus.FAILED.name());
            publisher.sendPlan(planId, Map.of("planId", planId, "status", PlanStatus.FAILED.name()));
            notifyPlanFail(planId);
        } else {
            planMapper.markFinished(planId, PlanStatus.COMPLETED.name());
            ReleasePlan plan = planMapper.selectById(planId);
            recordSummary(plan, plan.getRerunCount() + 1);
            publisher.sendPlan(planId, Map.of("planId", planId, "status", PlanStatus.COMPLETED.name()));
            log.info("发布计划完成: planId={}, planName={}", planId, plan.getPlanName());
        }
    }

    /** 单步执行：一个数字目录 = 一个事务。调用方需持有 planId 锁。 */
    private void runStep(ReleaseStep step) {
        long t0 = System.currentTimeMillis();
        stepMapper.markRunning(step.getId());
        publishStep(step, StepStatus.RUNNING.name());

        Path dir = Paths.get(step.getDirPath());
        if (!Files.isDirectory(dir)) {
            // 二次校验：目录执行前被删 → SKIPPED，继续下一步（RL-8）
            stepMapper.markSkipped(step.getId());
            publishStep(step, StepStatus.SKIPPED.name());
            log.warn("步骤目录已不存在，跳过: planId={}, stepNo={}, dir={}",
                    step.getPlanId(), step.getStepNo(), dir);
            return;
        }
        try (Connection conn = registry.get(step.getConnKey()).getConnection()) {
            try {
                conn.setAutoCommit(false);
                executeSqlFiles(conn, step, dir);
                conn.commit();
                stepMapper.markSuccess(step.getId(), System.currentTimeMillis() - t0);
                publishStep(step, StepStatus.SUCCESS.name());
                log.info("发布步骤成功: planId={}, stepNo={}, durationMs={}",
                        step.getPlanId(), step.getStepNo(), System.currentTimeMillis() - t0);
            } catch (Exception e) {
                safeRollback(conn);
                stepMapper.markFail(step.getId(), System.currentTimeMillis() - t0, brief(e));
                publishStep(step, StepStatus.FAIL.name());
                log.warn("发布步骤失败(已回滚): planId={}, stepNo={}, err={}",
                        step.getPlanId(), step.getStepNo(), brief(e));
            }
        } catch (SQLException | RuntimeException e) {
            // 目标库连接失败：步骤置 FAIL，不自动重试（避免雪崩）
            stepMapper.markFail(step.getId(), System.currentTimeMillis() - t0, brief(e));
            publishStep(step, StepStatus.FAIL.name());
            log.warn("发布步骤失败(连接失败): planId={}, stepNo={}, err={}",
                    step.getPlanId(), step.getStepNo(), brief(e));
        } finally {
            ReleaseStep after = stepMapper.selectById(step.getId());
            if (after != null) {
                recordStepMetrics(step.getPlanId(), step.getStepNo(), after.getStatus(), after.getDurationMs());
                // 告警钩子：步骤失败即告警（RELEASE_STEP_FAIL）
                if (StepStatus.FAIL.name().equals(after.getStatus())) {
                    ReleasePlan plan = planMapper.selectById(step.getPlanId());
                    alertService.onReleaseStepFail(step.getPlanId(),
                            plan == null ? null : plan.getPlanName(),
                            plan == null ? null : plan.getCrNumber(),
                            step.getStepNo(), step.getConnKey(),
                            after.getRetryCount(), after.getDurationMs(), after.getErrorMsg());
                }
            }
        }
    }

    private void executeSqlFiles(Connection conn, ReleaseStep step, Path dir) throws SQLException, IOException {
        int seq = 0;
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> sqlFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString()
                            .toLowerCase().endsWith(properties.getSqlSuffix().toLowerCase()))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (Path file : sqlFiles) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                for (String sql : SqlSplitter.split(content)) {
                    seq++;
                    executeSingle(conn, step, file, seq, sql);
                }
            }
        }
    }

    private void executeSingle(Connection conn, ReleaseStep step, Path file, int seq, String sql) throws SQLException {
        long t0 = System.currentTimeMillis();
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(properties.getStatementTimeoutSec());
            st.execute(sql);
        } catch (SQLException e) {
            insertSqlLog(step, file, seq, sql, "FAIL", System.currentTimeMillis() - t0, e.getMessage());
            throw e;
        }
        insertSqlLog(step, file, seq, sql, "SUCCESS", System.currentTimeMillis() - t0, null);
    }

    private void insertSqlLog(ReleaseStep step, Path file, int seq, String sql,
                              String status, long durationMs, String error) {
        try {
            ReleaseSqlLog logRow = new ReleaseSqlLog();
            logRow.setPlanId(step.getPlanId());
            logRow.setStepId(step.getId());
            logRow.setStepNo(step.getStepNo());
            logRow.setFileName(file.getFileName().toString());
            logRow.setSeq(seq);
            logRow.setSqlPreview(JsonUtils.truncate(sql, 1000));
            logRow.setStatus(status);
            logRow.setDurationMs(durationMs);
            logRow.setErrorMsg(error == null ? null : JsonUtils.truncate(error, 2000));
            sqlLogMapper.insert(logRow);
        } catch (Exception ex) {
            log.error("SQL 明细日志写入失败: stepId={}, file={}, err={}", step.getId(), file, ex.getMessage());
        }
    }

    /** 记录某次运行摘要：(plan_id, run_seq) 冲突时忽略（已完成运行的摘要优先保留）。 */
    private void recordSummary(ReleasePlan plan, int runSeq) {
        try {
            List<ReleaseStep> steps = stepMapper.selectByPlan(plan.getId());
            List<Map<String, Object>> detail = new ArrayList<>();
            long totalMs = 0;
            for (ReleaseStep s : steps) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("stepNo", s.getStepNo());
                item.put("status", s.getStatus());
                item.put("durationMs", s.getDurationMs());
                if (s.getErrorMsg() != null) {
                    item.put("error", s.getErrorMsg());
                }
                detail.add(item);
                if (s.getDurationMs() != null) {
                    totalMs += s.getDurationMs();
                }
            }
            ReleaseRunSummary summary = new ReleaseRunSummary();
            summary.setPlanId(plan.getId());
            summary.setRunSeq(runSeq);
            summary.setTotalMs(totalMs);
            summary.setStepDetail(JsonUtils.toJson(detail));
            summary.setStartedAt(plan.getStartedAt());
            summary.setFinishedAt(plan.getFinishedAt() != null ? plan.getFinishedAt() : LocalDateTime.now());
            summary.setOperator(plan.getOperator());
            summaryMapper.insert(summary);
        } catch (Exception e) {
            log.warn("运行摘要记录失败: planId={}, err={}", plan.getId(), e.getMessage());
        }
    }

    // ---------- 辅助 ----------

    private void assertExecutor(ReleaseStep step, String operator) {
        if (hasText(step.getExecutor()) && !step.getExecutor().equals(operator)) {
            throw new BizException(ErrorCode.RL_NOT_EXECUTOR,
                    "步骤 " + step.getStepNo() + " 的指定执行者为 " + step.getExecutor()
                            + "，当前操作者: " + operator);
        }
    }

    private void validateConnKey(String connKey) {
        if (!hasText(connKey)) {
            return;
        }
        if (connectionMapper.selectByKey(connKey) == null) {
            throw new BizException(ErrorCode.DS_NOT_FOUND, "连接不存在: " + connKey);
        }
    }

    /** 路径穿越防护（§12.4）。 */
    private Path requireBaseDir() {
        Path base = Paths.get(properties.getBasePath());
        try {
            return Files.exists(base) ? base.toRealPath() : base.toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYS_PARAM_INVALID,
                    "release.base-path 无法解析: " + properties.getBasePath());
        }
    }

    private Path resolvePlanDir(String basePath, String planName) {
        if (!PLAN_NAME.matcher(planName).matches()) {
            throw new BizException(ErrorCode.SYS_PARAM_INVALID,
                    "计划名只允许字母、数字、._-: " + planName);
        }
        Path base = Paths.get(basePath);
        try {
            base = Files.exists(base) ? base.toRealPath() : base.toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYS_PARAM_INVALID, "base-path 无法解析: " + basePath);
        }
        Path target = base.resolve(planName).normalize();
        if (!target.startsWith(base)) {
            throw new BizException(ErrorCode.SYS_PARAM_INVALID, "非法路径: " + planName);
        }
        return target;
    }

    private Map<Integer, StepConfig> indexConfigs(List<StepConfig> configs) {
        Map<Integer, StepConfig> map = new HashMap<>();
        if (configs != null) {
            for (StepConfig c : configs) {
                map.put(c.stepNo(), c);
            }
        }
        return map;
    }

    private void publishStep(ReleaseStep step, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("planId", step.getPlanId());
        payload.put("stepId", step.getId());
        payload.put("stepNo", step.getStepNo());
        payload.put("status", status);
        publisher.sendStep(step.getPlanId(), payload);
    }

    /** PLAN_FAIL 告警钩子。 */
    private void notifyPlanFail(Long planId) {
        try {
            ReleasePlan plan = planMapper.selectById(planId);
            if (plan != null) {
                alertService.onPlanFail(plan.getId(), plan.getPlanName(), plan.getCrNumber(), plan.getOperator());
            }
        } catch (Exception e) {
            log.debug("PLAN_FAIL 告警上报失败: planId={}", planId);
        }
    }

    private void recordStepMetrics(Long planId, int stepNo, String status, Long durationMs) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry == null) {
            return;
        }
        try {
            Counter.builder("release_step_total")
                    .tag("plan_id", String.valueOf(planId))
                    .tag("step_no", String.valueOf(stepNo))
                    .tag("status", status)
                    .register(registry).increment();
            if (durationMs != null) {
                DistributionSummary.builder("release_step_duration_ms")
                        .tag("plan_id", String.valueOf(planId))
                        .tag("step_no", String.valueOf(stepNo))
                        .register(registry).record(durationMs);
            }
        } catch (Exception ignore) {
            // 指标记录失败不影响业务
        }
    }

    private ReleasePlan requirePlan(Long planId) {
        ReleasePlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new BizException(ErrorCode.RL_PLAN_NOT_FOUND, "发布计划不存在: " + planId);
        }
        return plan;
    }

    private void safeRollback(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException e) {
            log.debug("rollback 失败: {}", e.getMessage());
        }
    }

    private String brief(Exception e) {
        String msg = e.getMessage();
        return JsonUtils.truncate(msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg, 2000);
    }
}

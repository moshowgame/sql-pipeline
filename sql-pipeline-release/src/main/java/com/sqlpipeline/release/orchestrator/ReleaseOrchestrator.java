package com.sqlpipeline.release.orchestrator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.common.util.JsonUtils;
import com.sqlpipeline.datasource.entity.DbConnection;
import com.sqlpipeline.datasource.mapper.DbConnectionMapper;
import com.sqlpipeline.datasource.registry.DataSourceRegistry;
import com.sqlpipeline.notify.service.AlertService;
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
import com.sqlpipeline.release.scanner.ScriptHash;
import com.sqlpipeline.datasource.guard.SqlSplitter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.MessageSource;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.springframework.util.StringUtils.hasText;

/**
 * 发布编排引擎：显式状态机 + plan 级互斥锁（ADR-04/07，PG advisory lock 实现且天然支持多实例），
 * 不引入流程引擎。步骤即事务边界：一个数字目录 = 一个事务，失败整目录回滚（ADR-05）。
 * <p>
 * 执行模型：HTTP 请求仅完成校验与元数据写入（同步、持锁），实际驱动（drive/runStep）
 * 提交到 release-driver 线程池异步执行，进度经 SSE 推送；advisory lock 保证同一计划的
 * 驱动互斥，且应用崩溃时锁随会话自动释放。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseOrchestrator {

    private static final Pattern PLAN_NAME = Pattern.compile("^[A-Za-z0-9._-]+$");
    /** 非事务模式标记文件：置于步骤目录内，该目录逐条自动提交、失败不回滚（用于 CONCURRENTLY 等非事务 DDL）。 */
    private static final String NON_TX_MARKER = "_nontransactional";
    private static final Pattern CONCURRENTLY = Pattern.compile("(?i)\\bCONCURRENTLY\\b");
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
    private final ThreadPoolTaskExecutor releaseDriverExecutor;
    private final AlertService alertService;
    private final MessageSource messageSource;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, key, org.springframework.context.i18n.LocaleContextHolder.getLocale());
    }

    // ---------- 创建与预览 ----------

    public ReleasePlan create(String planName, String defaultConnKey, List<StepConfig> stepConfigs, String operator) {
        Path base = requireBaseDir();
        validateConnKey(defaultConnKey);
        for (StepConfig c : stepConfigs == null ? List.<StepConfig>of() : stepConfigs) {
            if (c.stepNo() == null || c.stepNo() < 1) {
                throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.rl.stepNoInvalid", String.valueOf(c));
            }
            validateConnKey(c.connKey());
            if (hasText(c.afterMode()) && !AfterMode.CONTINUE.name().equalsIgnoreCase(c.afterMode())
                    && !AfterMode.WAIT.name().equalsIgnoreCase(c.afterMode())) {
                throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID,
                        "error.rl.afterModeInvalid", c.afterMode());
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

    /** 执行者提交 CR + Remark 后启动（RL-5）：同步完成校验与元数据，异步驱动执行。 */
    public void start(Long planId, String crNumber, String remark, String operator) {
        boolean drive = lockRegistry.withLock(planId, () -> prepareStart(planId, crNumber, remark, operator));
        if (drive) {
            submitDrive(planId);
        }
    }

    /** 手工继续（RL-6）：同步确认，异步驱动后续步骤。 */
    public void continueNext(Long planId, String operator) {
        boolean drive = lockRegistry.withLock(planId, () -> {
            ReleasePlan plan = requirePlan(planId);
            if (!PlanStatus.WAITING.name().equals(plan.getStatus())) {
                throw BizException.i18n(ErrorCode.RL_NO_WAITING_STEP,
                        "error.rl.notWaiting", plan.getStatus());
            }
            ReleaseStep waiting = stepMapper.selectWaiting(planId);
            if (waiting != null) {
                assertExecutor(waiting, operator);
                stepMapper.updateStatus(waiting.getId(), StepStatus.SUCCESS.name());
                stepMapper.markConfirmed(waiting.getId(), operator);
                publishStep(waiting, StepStatus.SUCCESS.name());
                log.info("WAIT 步骤人工确认通过: planId={}, stepNo={}, operator={}",
                        planId, waiting.getStepNo(), operator);
            } else {
                // retryStep 成功后计划处于 WAITING 但无等待步骤：校验下一步执行者后推进
                ReleaseStep next = stepMapper.selectNextActionable(planId);
                if (next == null) {
                    planMapper.updateStatus(planId, PlanStatus.RUNNING.name());
                    finishPlan(planId);
                    return false;
                }
                assertExecutor(next, operator);
            }
            planMapper.updateStatus(planId, PlanStatus.RUNNING.name());
            return true;
        });
        if (Boolean.TRUE.equals(drive)) {
            submitDrive(planId);
        }
    }

    /** 单步重试：仅重跑该步，不触发 after_mode 编排（RL-10 / ADR-06）；执行异步。 */
    public void retryStep(Long planId, int stepNo, String operator, String remark) {
        lockRegistry.withLock(planId, () -> {
            requirePlan(planId);
            ReleaseStep step = stepMapper.selectByNo(planId, stepNo);
            if (step == null) {
                throw BizException.i18n(ErrorCode.RL_STEP_NOT_FOUND, "error.rl.stepNotFound", stepNo);
            }
            if (!StepStatus.FAIL.name().equals(step.getStatus())) {
                throw BizException.i18n(ErrorCode.RL_STEP_NOT_RETRYABLE,
                        "error.rl.stepNotRetryable", stepNo, step.getStatus());
            }
            assertExecutor(step, operator);
            stepMapper.incrementRetry(step.getId(), remark);
            return null;
        });
        // 异步执行：成功后计划置 WAITING，由人工决定是否继续（不自动推进，RL-10）
        submitDriver(planId, () -> {
            ReleaseStep step = stepMapper.selectByNo(planId, stepNo);
            runStep(step);
            ReleaseStep after = stepMapper.selectById(step.getId());
            if (after != null && StepStatus.SUCCESS.name().equals(after.getStatus())) {
                planMapper.updateStatus(planId, PlanStatus.WAITING.name());
                publisher.sendPlan(planId, Map.of("planId", planId, "status", PlanStatus.WAITING.name()));
            }
        });
    }

    /** 全流程重跑（RL-9）：同步记录摘要并重置，异步从头执行。 */
    public void rerunAll(Long planId, String operator) {
        String crNumber = lockRegistry.withLock(planId, () -> {
            ReleasePlan plan = requirePlan(planId);
            if (PlanStatus.RUNNING.name().equals(plan.getStatus())) {
                throw BizException.i18n(ErrorCode.RL_PLAN_ALREADY_STARTED, "error.rl.rerunNotAllowed");
            }
            recordSummary(plan, plan.getRerunCount() + 1);
            stepMapper.deleteByPlan(planId);
            planMapper.resetForRerun(planId);
            log.info("发布计划全流程重跑: planId={}, runSeq={}, operator={}",
                    planId, plan.getRerunCount() + 1, operator);
            return plan.getCrNumber();
        });
        submitDriver(planId, () -> startInternal(planId, crNumber, "UAT rerun by " + operator, operator));
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

    public List<ReleaseSqlLog> logs(Long planId, Integer stepNo, Integer runSeq) {
        requirePlan(planId);
        return sqlLogMapper.selectByPlan(planId, stepNo, runSeq);
    }

    public List<ReleaseRunSummary> summaries(Long planId) {
        requirePlan(planId);
        return summaryMapper.selectByPlan(planId);
    }

    /** 脚本变更检测：当前目录内容哈希与 start 时存档不一致（存档缺失/目录缺失返回 false）。 */
    public boolean isScriptChanged(ReleaseStep step) {
        if (!hasText(step.getScriptHash())) {
            return false;
        }
        String current = ScriptHash.of(Paths.get(step.getDirPath()), properties.getSqlSuffix());
        return current != null && !current.equals(step.getScriptHash());
    }

    // ---------- 启动恢复 ----------

    /**
     * 崩溃恢复：应用重启后
     * 1) 卡在 RUNNING 的步骤 → FAIL（可人工重试）；
     * 2) 仍处于 RUNNING 的计划：有 FAIL 步骤 → FAILED；仍有待执行步骤 → WAITING（人工 continue 恢复推进）；
     *    全部步骤已终态 → 补记 COMPLETED。
     * 注意：崩溃可能发生在目标库 commit 之后、markSuccess 之前，此时步骤实际已提交——
     * 恢复提示明确要求人工核对后再重试。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        try {
            List<ReleaseStep> running = stepMapper.selectRunning();
            for (ReleaseStep step : running) {
                stepMapper.markFail(step.getId(), null, msg("error.rl.interruptedWarning"));
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

    // ---------- 内部：状态机驱动（必须持锁） ----------

    /**
     * 启动元数据：校验 DRAFT → 扫描 → 步骤落库（含脚本哈希）→ 计划置 RUNNING。
     * 返回是否需要驱动（目录为空时计划已置 SKIPPED，返回 false）。调用方需持锁。
     */
    private boolean prepareStart(Long planId, String crNumber, String remark, String operator) {
        ReleasePlan plan = requirePlan(planId);
        if (!PlanStatus.DRAFT.name().equals(plan.getStatus())) {
            throw BizException.i18n(ErrorCode.RL_PLAN_ALREADY_STARTED,
                    "error.rl.notDraft", plan.getStatus());
        }
        List<ScannedStep> scanned = scanner.scan(resolvePlanDir(plan.getBasePath(), plan.getPlanName()));
        if (scanned.isEmpty()) {
            // 整个 plan 目录不存在 / 无有效步骤 → SKIPPED（RL-8）
            planMapper.markFinished(planId, PlanStatus.SKIPPED.name());
            publisher.sendPlan(planId, Map.of("planId", planId, "status", PlanStatus.SKIPPED.name()));
            log.info("发布计划目录不存在或无 SQL，标记 SKIPPED: planId={}, planName={}", planId, plan.getPlanName());
            return false;
        }
        Map<Integer, StepConfig> cfgs = indexConfigs(parseStepConfigs(plan.getStepConfig()));
        List<ReleaseStep> steps = new ArrayList<>();
        for (ScannedStep s : scanned) {
            StepConfig c = cfgs.get(s.no());
            String connKey = c != null && hasText(c.connKey()) ? c.connKey() : plan.getDefaultConnKey();
            if (!hasText(connKey)) {
                throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.rl.connRequired", s.no());
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
            step.setScriptHash(ScriptHash.of(s.dir(), properties.getSqlSuffix()));
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
        return true;
    }

    private void startInternal(Long planId, String crNumber, String remark, String operator) {
        if (prepareStart(planId, crNumber, remark, operator)) {
            driveInternal(planId);
        }
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
        ReleasePlan plan = planMapper.selectById(planId);
        if (fails > 0) {
            planMapper.updateStatus(planId, PlanStatus.FAILED.name());
            publisher.sendPlan(planId, Map.of("planId", planId, "status", PlanStatus.FAILED.name()));
            notifyPlanFail(planId);
            // 失败的运行同样记入摘要（UAT 统计完整性）
            recordSummary(plan, plan.getRerunCount() + 1);
        } else {
            planMapper.markFinished(planId, PlanStatus.COMPLETED.name());
            recordSummary(plan, plan.getRerunCount() + 1);
            alertService.onPlanCompleted(plan.getId(), plan.getPlanName(), plan.getCrNumber(), plan.getOperator());
            publisher.sendPlan(planId, Map.of("planId", planId, "status", PlanStatus.COMPLETED.name()));
            log.info("发布计划完成: planId={}, planName={}", planId, plan.getPlanName());
        }
    }

    /** 单步执行：一个数字目录 = 一个事务（ADR-05）；目录含 _nontransactional 标记时逐条自动提交。调用方需持 planId 锁。 */
    private void runStep(ReleaseStep step) {
        long t0 = System.currentTimeMillis();
        ReleasePlan plan = planMapper.selectById(step.getPlanId());
        int runSeq = (plan != null && plan.getRerunCount() != null ? plan.getRerunCount() : 0) + 1;
        String operator = plan == null ? null : plan.getOperator();
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
        boolean nonTx = Files.exists(dir.resolve(NON_TX_MARKER));
        long totalTimeoutSec = properties.getStepTotalTimeoutSec();
        long deadline = totalTimeoutSec > 0 ? t0 + totalTimeoutSec * 1000L : 0L;
        if (nonTx) {
            log.warn("非事务模式执行（目录含 {}，失败不回滚）: planId={}, stepNo={}",
                    NON_TX_MARKER, step.getPlanId(), step.getStepNo());
        }
        try (Connection conn = registry.get(step.getConnKey()).getConnection()) {
            try {
                conn.setAutoCommit(nonTx);
                executeSqlFiles(conn, step, dir, nonTx, deadline, runSeq, operator);
                if (!nonTx) {
                    conn.commit();
                }
                stepMapper.markSuccess(step.getId(), System.currentTimeMillis() - t0);
                publishStep(step, StepStatus.SUCCESS.name());
                log.info("发布步骤成功: planId={}, stepNo={}, nonTx={}, durationMs={}",
                        step.getPlanId(), step.getStepNo(), nonTx, System.currentTimeMillis() - t0);
            } catch (Exception e) {
                if (!nonTx) {
                    safeRollback(conn);
                }
                String message = (nonTx ? msg("error.rl.nonTxPrefix") : "") + brief(e);
                stepMapper.markFail(step.getId(), System.currentTimeMillis() - t0, message);
                publishStep(step, StepStatus.FAIL.name());
                log.warn("发布步骤失败(已{}): planId={}, stepNo={}, err={}",
                        nonTx ? "逐条提交" : "回滚", step.getPlanId(), step.getStepNo(), message);
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
                // 告警钩子：失败 / 成功事件（通道按订阅过滤）
                if (StepStatus.FAIL.name().equals(after.getStatus())) {
                    alertService.onReleaseStepFail(step.getPlanId(),
                            plan == null ? null : plan.getPlanName(),
                            plan == null ? null : plan.getCrNumber(),
                            step.getStepNo(), step.getConnKey(),
                            after.getRetryCount(), after.getDurationMs(), after.getErrorMsg());
                } else if (StepStatus.SUCCESS.name().equals(after.getStatus())) {
                    alertService.onReleaseStepSuccess(step.getPlanId(),
                            plan == null ? null : plan.getPlanName(),
                            plan == null ? null : plan.getCrNumber(),
                            step.getStepNo(), step.getConnKey(), after.getDurationMs());
                }
            }
        }
    }

    private void executeSqlFiles(Connection conn, ReleaseStep step, Path dir,
                                 boolean nonTx, long deadline, Integer runSeq, String operator) throws SQLException, IOException {
        int seq = 0;
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> sqlFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals(NON_TX_MARKER))
                    .filter(p -> p.getFileName().toString()
                            .toLowerCase().endsWith(properties.getSqlSuffix().toLowerCase()))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (Path file : sqlFiles) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                for (String sql : SqlSplitter.split(content)) {
                    // 语句间检查步骤总超时（运行中语句由自身 queryTimeout 约束）
                    if (deadline > 0 && System.currentTimeMillis() > deadline) {
                        throw new SQLException(msg("error.rl.stepTimeout", properties.getStepTotalTimeoutSec()));
                    }
                    // 事务模式下拒绝 CONCURRENTLY 等非事务 DDL（PostgreSQL 限制）
                    if (!nonTx && CONCURRENTLY.matcher(sql).find()) {
                        throw new SQLException(msg("error.rl.concurrentlyInTx"));
                    }
                    seq++;
                    executeSingle(conn, step, file, seq, sql, runSeq, operator);
                }
            }
        }
    }

    private void executeSingle(Connection conn, ReleaseStep step, Path file, int seq, String sql,
                               Integer runSeq, String operator) throws SQLException {
        long t0 = System.currentTimeMillis();
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(properties.getStatementTimeoutSec());
            st.execute(sql);
        } catch (SQLException e) {
            insertSqlLog(step, file, seq, sql, "FAIL", System.currentTimeMillis() - t0, e.getMessage(), runSeq, operator);
            throw e;
        }
        insertSqlLog(step, file, seq, sql, "SUCCESS", System.currentTimeMillis() - t0, null, runSeq, operator);
    }

    private void insertSqlLog(ReleaseStep step, Path file, int seq, String sql,
                              String status, long durationMs, String error, Integer runSeq, String operator) {
        try {
            ReleaseSqlLog logRow = new ReleaseSqlLog();
            logRow.setPlanId(step.getPlanId());
            logRow.setStepId(step.getId());
            logRow.setStepNo(step.getStepNo());
            logRow.setRunSeq(runSeq);
            logRow.setOperator(operator);
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
            throw BizException.i18n(ErrorCode.RL_NOT_EXECUTOR,
                    "error.rl.notExecutor", step.getStepNo(), step.getExecutor(), operator);
        }
    }

    private void validateConnKey(String connKey) {
        if (!hasText(connKey)) {
            return;
        }
        if (connectionMapper.selectByKey(connKey) == null) {
            throw BizException.i18n(ErrorCode.DS_NOT_FOUND, "error.ds.notFound", connKey);
        }
    }

    /** 路径穿越防护（§12.4）。 */
    private Path requireBaseDir() {
        Path base = Paths.get(properties.getBasePath());
        try {
            return Files.exists(base) ? base.toRealPath() : base.toAbsolutePath().normalize();
        } catch (IOException e) {
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID,
                    "error.rl.basePathInvalid", properties.getBasePath());
        }
    }

    private Path resolvePlanDir(String basePath, String planName) {
        if (!PLAN_NAME.matcher(planName).matches()) {
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID,
                    "error.rl.planNameInvalid", planName);
        }
        Path base = Paths.get(basePath);
        try {
            base = Files.exists(base) ? base.toRealPath() : base.toAbsolutePath().normalize();
        } catch (IOException e) {
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.rl.basePathInvalid", basePath);
        }
        Path target = base.resolve(planName).normalize();
        if (!target.startsWith(base)) {
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.rl.pathInvalid", planName);
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
            throw BizException.i18n(ErrorCode.RL_PLAN_NOT_FOUND, "error.rl.planNotFound", planId);
        }
        return plan;
    }

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

    /** 提交驱动任务到 release-driver 线程池（持 planId 锁执行）。 */
    private void submitDrive(Long planId) {
        submitDriver(planId, () -> driveInternal(planId));
    }

    private void submitDriver(Long planId, Runnable task) {
        releaseDriverExecutor.execute(() -> {
            try {
                lockRegistry.withLock(planId, () -> {
                    task.run();
                    return null;
                });
            } catch (Exception e) {
                log.error("发布驱动任务异常: planId={}", planId, e);
            }
        });
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

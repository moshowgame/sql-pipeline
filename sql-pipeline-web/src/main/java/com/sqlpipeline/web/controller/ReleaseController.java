package com.sqlpipeline.web.controller;

import com.sqlpipeline.common.api.R;
import com.sqlpipeline.release.entity.ReleasePlan;
import com.sqlpipeline.release.entity.ReleaseRunSummary;
import com.sqlpipeline.release.entity.ReleaseSqlLog;
import com.sqlpipeline.release.entity.ReleaseStep;
import com.sqlpipeline.release.event.ReleaseEventPublisher;
import com.sqlpipeline.release.orchestrator.ReleaseOrchestrator;
import com.sqlpipeline.release.orchestrator.StepConfig;
import com.sqlpipeline.release.scanner.ScannedStepView;
import com.sqlpipeline.release.zip.NexusZipFetcher;
import com.sqlpipeline.web.dto.ReleaseCreateReq;
import com.sqlpipeline.web.dto.ReleaseFetchZipReq;
import com.sqlpipeline.web.dto.ReleaseScanReq;
import com.sqlpipeline.web.dto.ReleaseStartReq;
import com.sqlpipeline.web.dto.ReleaseUpdateReq;
import com.sqlpipeline.web.dto.RetryReq;
import com.sqlpipeline.web.dto.StepConfigReq;
import com.sqlpipeline.web.report.ReleaseReportService;
import com.sqlpipeline.web.util.Operator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/releases")
@RequiredArgsConstructor
public class ReleaseController {

    private final ReleaseOrchestrator orchestrator;
    private final ReleaseEventPublisher publisher;
    private final ReleaseReportService reportService;
    private final NexusZipFetcher nexusZipFetcher;

    /** Nexus 发布包下载 + 解压目录检测：返回 zip 缓存路径（回填 release_path）与步骤预览；凭据不落库不打日志。 */
    @PostMapping("/fetch-zip")
    public R<Map<String, Object>> fetchZip(@Valid @RequestBody ReleaseFetchZipReq req) {
        String zipPath = nexusZipFetcher.fetch(req.nexusUrl(), req.username(), req.token());
        List<ScannedStepView> steps = orchestrator.preview("ZIP", zipPath, null, List.of());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("releasePath", zipPath);
        result.put("steps", steps);
        return R.ok(result);
    }

    /** 预览 release path 下的数字子目录（asc），结果作为 release step（不落库）。 */
    @PostMapping("/scan")
    public R<List<ScannedStepView>> scan(@Valid @RequestBody ReleaseScanReq req) {
        return R.ok(orchestrator.preview(req.releaseType(), req.releasePath(),
                req.defaultConnKey(), List.of()));
    }

    @PostMapping
    public R<ReleasePlan> create(@Valid @RequestBody ReleaseCreateReq req,
                                 @RequestHeader(value = Operator.HEADER, required = false) String operator) {
        return R.ok(orchestrator.create(req.planName(), req.releaseType(), req.releasePath(),
                req.defaultConnKey(), toConfigs(req.steps()), Operator.of(operator)));
    }

    /** 编辑计划（仅 DRAFT）：更新 Release 类型/路径/默认连接/步骤配置。 */
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody ReleaseUpdateReq req,
                          @RequestHeader(value = Operator.HEADER, required = false) String operator) {
        orchestrator.update(id, req.releaseType(), req.releasePath(),
                req.defaultConnKey(), toConfigs(req.steps()), Operator.of(operator));
        return R.ok();
    }

    @GetMapping
    public R<List<ReleasePlan>> list() {
        return R.ok(orchestrator.listPlans());
    }

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        Map<String, Object> detail = new LinkedHashMap<>();
        ReleasePlan plan = orchestrator.getPlan(id);
        List<ReleaseStep> steps = orchestrator.listSteps(id);
        // 脚本变更检测：当前目录哈希与 start 时存档对比
        steps.forEach(s -> s.setScriptChanged(orchestrator.isScriptChanged(s)));
        detail.put("plan", plan);
        detail.put("steps", steps);
        detail.put("stepConfigs", orchestrator.parseStepConfigs(plan.getStepConfig()));
        return R.ok(detail);
    }

    @PostMapping("/{id}/start")
    public R<Void> start(@PathVariable Long id, @Valid @RequestBody ReleaseStartReq req,
                         @RequestHeader(value = Operator.HEADER, required = false) String operator) {
        orchestrator.start(id, req.crNumber(), req.remark(), Operator.of(operator), req.dryRun());
        return R.ok();
    }

    @PostMapping("/{id}/continue")
    public R<Void> continueNext(@PathVariable Long id,
                                @RequestHeader(value = Operator.HEADER, required = false) String operator) {
        orchestrator.continueNext(id, Operator.of(operator));
        return R.ok();
    }

    @PostMapping("/{id}/steps/{no}/retry")
    public R<Void> retry(@PathVariable Long id, @PathVariable int no,
                         @RequestBody(required = false) RetryReq req,
                         @RequestHeader(value = Operator.HEADER, required = false) String operator) {
        orchestrator.retryStep(id, no, Operator.of(operator), req == null ? null : req.remark());
        return R.ok();
    }

    @PostMapping("/{id}/rerun")
    public R<Void> rerun(@PathVariable Long id,
                         @RequestHeader(value = Operator.HEADER, required = false) String operator) {
        orchestrator.rerunAll(id, Operator.of(operator));
        return R.ok();
    }

    @GetMapping("/{id}/logs")
    public R<List<ReleaseSqlLog>> logs(@PathVariable Long id, @RequestParam(required = false) Integer stepNo,
                                       @RequestParam(required = false) Integer runSeq,
                                       @RequestParam(required = false) String status) {
        return R.ok(orchestrator.logs(id, stepNo, runSeq, status));
    }

    /** 各步骤语句总数/失败数（最新一轮）：Pipeline 节点展示失败率。 */
    @GetMapping("/{id}/logs/stats")
    public R<List<Map<String, Object>>> logStats(@PathVariable Long id) {
        return R.ok(orchestrator.logStepStats(id));
    }

    @GetMapping("/{id}/summaries")
    public R<List<ReleaseRunSummary>> summaries(@PathVariable Long id) {
        return R.ok(orchestrator.summaries(id));
    }

    /** SSE 状态推送：step / plan 两类事件。 */
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long id) {
        return publisher.subscribe(id);
    }

    /** 自包含 HTML 执行报告（浏览器渲染，可另存归档）。 */
    @GetMapping(value = "/{id}/report", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> report(@PathVariable Long id) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"release-report-" + id + ".html\"")
                .body(reportService.buildHtml(id));
    }

    private List<StepConfig> toConfigs(List<StepConfigReq> reqs) {
        return reqs == null ? List.of() : reqs.stream().map(StepConfigReq::toConfig).toList();
    }
}

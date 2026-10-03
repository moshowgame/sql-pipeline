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
import com.sqlpipeline.web.dto.ReleaseCreateReq;
import com.sqlpipeline.web.dto.ReleaseScanReq;
import com.sqlpipeline.web.dto.ReleaseStartReq;
import com.sqlpipeline.web.dto.RetryReq;
import com.sqlpipeline.web.dto.StepConfigReq;
import com.sqlpipeline.web.util.Operator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    /** 预览目录结构与步骤配置合并结果（不落库）。 */
    @PostMapping("/scan")
    public R<List<ScannedStepView>> scan(@Valid @RequestBody ReleaseScanReq req) {
        return R.ok(orchestrator.preview(req.planName(), req.defaultConnKey(), toConfigs(req.steps())));
    }

    @PostMapping
    public R<ReleasePlan> create(@Valid @RequestBody ReleaseCreateReq req,
                                 @RequestHeader(value = Operator.HEADER, required = false) String operator) {
        return R.ok(orchestrator.create(req.planName(), req.defaultConnKey(), toConfigs(req.steps()),
                Operator.of(operator)));
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
        orchestrator.start(id, req.crNumber(), req.remark(), Operator.of(operator));
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
                                       @RequestParam(required = false) Integer runSeq) {
        return R.ok(orchestrator.logs(id, stepNo, runSeq));
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

    private List<StepConfig> toConfigs(List<StepConfigReq> reqs) {
        return reqs == null ? List.of() : reqs.stream().map(StepConfigReq::toConfig).toList();
    }
}

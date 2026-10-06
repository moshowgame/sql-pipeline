package com.sqlpipeline.web.controller;

import com.sqlpipeline.common.api.PageResult;
import com.sqlpipeline.common.api.R;
import com.sqlpipeline.health.entity.HealthCheckRun;
import com.sqlpipeline.health.entity.SqlDefinition;
import com.sqlpipeline.health.entity.SqlDefinitionHistory;
import com.sqlpipeline.health.service.HealthCheckService;
import com.sqlpipeline.web.dto.HealthCheckRunReq;
import com.sqlpipeline.web.dto.HealthCheckSaveReq;
import com.sqlpipeline.web.util.Operator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/health-checks")
@RequiredArgsConstructor
public class HealthCheckController {

    private final HealthCheckService healthCheckService;

    @GetMapping
    public R<List<SqlDefinition>> list() {
        return R.ok(healthCheckService.list());
    }

    @GetMapping("/{id}")
    public R<SqlDefinition> get(@PathVariable Long id) {
        return R.ok(healthCheckService.get(id));
    }

    @PostMapping
    public R<Long> create(@Valid @RequestBody HealthCheckSaveReq req,
                          @RequestHeader(value = Operator.HEADER, required = false) String operator) {
        return R.ok(healthCheckService.create(req.toEntity(), Operator.of(operator)));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody HealthCheckSaveReq req,
                          @RequestHeader(value = Operator.HEADER, required = false) String operator) {
        SqlDefinition def = req.toEntity();
        def.setId(id);
        healthCheckService.update(def, Operator.of(operator));
        return R.ok();
    }

    /** 手动执行（触发方式 MANUAL），可携带参数覆盖默认参数。 */
    @PostMapping("/{id}/run")
    public R<HealthCheckRun> run(@PathVariable Long id,
                                 @RequestBody(required = false) HealthCheckRunReq req) {
        return R.ok(healthCheckService.run(id, "MANUAL", req == null ? null : req.params()));
    }

    @GetMapping("/{id}/runs")
    public R<PageResult<HealthCheckRun>> runs(@PathVariable Long id,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int size,
                                              @RequestParam(required = false) String day) {
        return R.ok(healthCheckService.pageRuns(id, page, size, day));
    }

    @GetMapping("/{id}/history")
    public R<PageResult<SqlDefinitionHistory>> history(@PathVariable Long id,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size,
                                                       @RequestParam(required = false) String day) {
        return R.ok(healthCheckService.history(id, page, size, day));
    }

    @PostMapping("/{id}/enable")
    public R<Void> enable(@PathVariable Long id,
                          @RequestHeader(value = Operator.HEADER, required = false) String operator) {
        healthCheckService.enable(id, Operator.of(operator));
        return R.ok();
    }

    @PostMapping("/{id}/disable")
    public R<Void> disable(@PathVariable Long id,
                           @RequestHeader(value = Operator.HEADER, required = false) String operator) {
        healthCheckService.disable(id, Operator.of(operator));
        return R.ok();
    }
}

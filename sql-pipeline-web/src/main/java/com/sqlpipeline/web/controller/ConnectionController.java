package com.sqlpipeline.web.controller;

import com.sqlpipeline.common.api.R;
import com.sqlpipeline.datasource.entity.DbConnection;
import com.sqlpipeline.datasource.service.ConnectionService;
import com.sqlpipeline.web.dto.ConnectionCreateReq;
import com.sqlpipeline.web.dto.ConnectionUpdateReq;
import com.sqlpipeline.web.dto.ConnectionView;
import com.sqlpipeline.web.util.Operator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/connections")
@RequiredArgsConstructor
public class ConnectionController {

    private final ConnectionService connectionService;

    @GetMapping
    public R<List<ConnectionView>> list() {
        return R.ok(connectionService.list().stream().map(ConnectionView::from).toList());
    }

    @GetMapping("/{id}")
    public R<ConnectionView> get(@PathVariable Long id) {
        return R.ok(ConnectionView.from(connectionService.get(id)));
    }

    @PostMapping
    public R<Long> create(@Valid @RequestBody ConnectionCreateReq req,
                          @RequestHeader(value = Operator.HEADER, required = false) String operator) {
        DbConnection entity = new DbConnection();
        entity.setConnKey(req.connKey());
        entity.setDisplayName(req.displayName());
        entity.setJdbcUrl(req.jdbcUrl());
        entity.setUsername(req.username());
        entity.setDriverClass(req.driverClass());
        entity.setPoolSize(req.poolSize());
        entity.setConnTimeoutMs(req.connTimeoutMs());
        entity.setMaxRows(req.maxRows());
        entity.setQueryTimeoutS(req.queryTimeoutS());
        entity.setEnabled(req.enabled());
        return R.ok(connectionService.create(entity, req.password(), Operator.of(operator)));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody ConnectionUpdateReq req,
                          @RequestHeader(value = Operator.HEADER, required = false) String operator) {
        DbConnection patch = new DbConnection();
        patch.setDisplayName(req.displayName());
        patch.setJdbcUrl(req.jdbcUrl());
        patch.setUsername(req.username());
        patch.setDriverClass(req.driverClass());
        patch.setPoolSize(req.poolSize());
        patch.setConnTimeoutMs(req.connTimeoutMs());
        patch.setMaxRows(req.maxRows());
        patch.setQueryTimeoutS(req.queryTimeoutS());
        patch.setEnabled(req.enabled());
        connectionService.update(id, patch, req.password(), Operator.of(operator));
        return R.ok();
    }

    @PostMapping("/{id}/test")
    public R<ConnectionService.TestResult> test(@PathVariable Long id) {
        return R.ok(connectionService.test(id));
    }

    @PostMapping("/{id}/reload")
    public R<Void> reload(@PathVariable Long id) {
        connectionService.reload(id);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id,
                          @RequestHeader(value = Operator.HEADER, required = false) String operator) {
        connectionService.disable(id, Operator.of(operator));
        return R.ok();
    }
}

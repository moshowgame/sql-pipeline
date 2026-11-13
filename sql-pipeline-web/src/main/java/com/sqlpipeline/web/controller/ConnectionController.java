package com.sqlpipeline.web.controller;

import com.sqlpipeline.common.api.R;
import com.sqlpipeline.datasource.entity.DbConnection;
import com.sqlpipeline.datasource.executor.AdHocQueryService;
import com.sqlpipeline.datasource.executor.QueryResult;
import com.sqlpipeline.datasource.metadata.DbMetadataService;
import com.sqlpipeline.datasource.service.ConnectionService;
import com.sqlpipeline.web.dto.AdHocQueryReq;
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
    private final DbMetadataService metadataService;
    private final AdHocQueryService adHocQueryService;

    @GetMapping
    public R<List<ConnectionView>> list() {
        return R.ok(connectionService.list().stream().map(ConnectionView::from).toList());
    }

    /** 表/字段元数据（SQL 编辑器补全），缓存 5 分钟。 */
    @GetMapping("/key/{connKey}/metadata")
    public R<List<DbMetadataService.TableMeta>> metadata(@PathVariable String connKey) {
        return R.ok(metadataService.tables(connKey));
    }

    /** 即席只读查询（多语句 SELECT，L2 AST 拦截 + 只读事务兜底）。 */
    @PostMapping("/key/{connKey}/query")
    public R<List<QueryResult>> query(@PathVariable String connKey,
                                      @Valid @RequestBody AdHocQueryReq req) {
        return R.ok(adHocQueryService.run(connKey, req.sql(), req.maxRows(), req.timeoutSec()));
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
        entity.setDefaultSchema(req.defaultSchema());
        entity.setPoolSize(req.poolSize());
        entity.setConnTimeoutMs(req.connTimeoutMs());
        entity.setMaxRows(req.maxRows());
        entity.setQueryTimeoutS(req.queryTimeoutS());
        entity.setEnabled(req.enabled());
        entity.setEnv(req.env());
        Long id = connectionService.create(entity, req.password(), Operator.of(operator));
        metadataService.evict(entity.getConnKey());
        return R.ok(id);
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody ConnectionUpdateReq req,
                          @RequestHeader(value = Operator.HEADER, required = false) String operator) {
        DbConnection patch = new DbConnection();
        patch.setDisplayName(req.displayName());
        patch.setJdbcUrl(req.jdbcUrl());
        patch.setUsername(req.username());
        patch.setDriverClass(req.driverClass());
        patch.setDefaultSchema(req.defaultSchema());
        patch.setPoolSize(req.poolSize());
        patch.setConnTimeoutMs(req.connTimeoutMs());
        patch.setMaxRows(req.maxRows());
        patch.setQueryTimeoutS(req.queryTimeoutS());
        patch.setEnabled(req.enabled());
        patch.setEnv(req.env());
        connectionService.update(id, patch, req.password(), Operator.of(operator));
        metadataService.evict(connectionService.get(id).getConnKey());
        return R.ok();
    }

    @PostMapping("/{id}/test")
    public R<ConnectionService.TestResult> test(@PathVariable Long id) {
        return R.ok(connectionService.test(id));
    }

    @PostMapping("/{id}/reload")
    public R<Void> reload(@PathVariable Long id) {
        connectionService.reload(id);
        metadataService.evict(connectionService.get(id).getConnKey());
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id,
                          @RequestHeader(value = Operator.HEADER, required = false) String operator) {
        String connKey = connectionService.get(id).getConnKey();
        connectionService.disable(id, Operator.of(operator));
        metadataService.evict(connKey);
        return R.ok();
    }
}

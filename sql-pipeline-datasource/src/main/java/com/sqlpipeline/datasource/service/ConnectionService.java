package com.sqlpipeline.datasource.service;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.datasource.crypto.CryptoService;
import com.sqlpipeline.datasource.entity.DbConnection;
import com.sqlpipeline.datasource.mapper.DbConnectionMapper;
import com.sqlpipeline.datasource.registry.DataSourceRegistry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.springframework.util.StringUtils.hasText;

/** 连接管理：CRUD + 凭据加密 + 连接池热更新 + 连通性测试。 */
@Service
@RequiredArgsConstructor
public class ConnectionService {

    private final DbConnectionMapper mapper;
    private final CryptoService crypto;
    private final DataSourceRegistry registry;

    public Long create(DbConnection entity, String plainPassword, String operator) {
        if (!hasText(entity.getConnKey())) {
            throw new BizException(ErrorCode.SYS_PARAM_INVALID, "connKey 不能为空");
        }
        if (!hasText(plainPassword)) {
            throw new BizException(ErrorCode.SYS_PARAM_INVALID, "密码不能为空");
        }
        if (mapper.selectByKey(entity.getConnKey()) != null) {
            throw new BizException(ErrorCode.SYS_PARAM_INVALID, "connKey 已存在: " + entity.getConnKey());
        }
        entity.setPasswordEnc(crypto.encrypt(plainPassword));
        if (entity.getEnabled() == null) {
            entity.setEnabled(1);
        }
        if (!hasText(entity.getDriverClass())) {
            entity.setDriverClass("org.postgresql.Driver");
        }
        // INSERT 显式列出这些列，这里补默认值避免写入 NULL
        if (entity.getPoolSize() == null) entity.setPoolSize(10);
        if (entity.getConnTimeoutMs() == null) entity.setConnTimeoutMs(5000);
        if (entity.getMaxRows() == null) entity.setMaxRows(1000);
        if (entity.getQueryTimeoutS() == null) entity.setQueryTimeoutS(30);
        entity.setCreatedBy(operator);
        entity.setUpdatedBy(operator);
        mapper.insert(entity);
        registry.register(entity);
        return entity.getId();
    }

    /** connKey 不可修改；密码留空表示保持不变。更新后热更新连接池。 */
    public void update(Long id, DbConnection patch, String plainPassword, String operator) {
        DbConnection old = mapper.selectById(id);
        if (old == null) {
            throw new BizException(ErrorCode.DS_NOT_FOUND, "连接不存在: " + id);
        }
        old.setDisplayName(hasText(patch.getDisplayName()) ? patch.getDisplayName() : old.getDisplayName());
        old.setJdbcUrl(hasText(patch.getJdbcUrl()) ? patch.getJdbcUrl() : old.getJdbcUrl());
        old.setUsername(hasText(patch.getUsername()) ? patch.getUsername() : old.getUsername());
        if (hasText(patch.getDriverClass())) {
            old.setDriverClass(patch.getDriverClass());
        }
        if (patch.getPoolSize() != null) old.setPoolSize(patch.getPoolSize());
        if (patch.getConnTimeoutMs() != null) old.setConnTimeoutMs(patch.getConnTimeoutMs());
        if (patch.getMaxRows() != null) old.setMaxRows(patch.getMaxRows());
        if (patch.getQueryTimeoutS() != null) old.setQueryTimeoutS(patch.getQueryTimeoutS());
        if (patch.getEnabled() != null) old.setEnabled(patch.getEnabled());
        if (hasText(plainPassword)) {
            old.setPasswordEnc(crypto.encrypt(plainPassword));
        }
        old.setUpdatedBy(operator);
        mapper.update(old);
        registry.reload(old.getConnKey());
    }

    /** 软删（enabled=0）并移除连接池。 */
    public void disable(Long id, String operator) {
        DbConnection old = requireExists(id);
        mapper.updateEnabled(id, 0, operator);
        registry.reload(old.getConnKey());
    }

    public void reload(Long id) {
        registry.reload(requireExists(id).getConnKey());
    }

    public TestResult test(Long id) {
        DbConnection e = requireExists(id);
        long t0 = System.currentTimeMillis();
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(e.getJdbcUrl());
        cfg.setUsername(e.getUsername());
        cfg.setPassword(crypto.decrypt(e.getPasswordEnc()));
        cfg.setDriverClassName(e.getDriverClass());
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(e.getConnTimeoutMs() != null ? e.getConnTimeoutMs() : 5000);
        cfg.setValidationTimeout(5000);
        cfg.setPoolName("conn-test-" + e.getConnKey());
        cfg.setInitializationFailTimeout(-1);
        try (HikariDataSource ds = new HikariDataSource(cfg)) {
            try (Connection conn = ds.getConnection()) {
                boolean valid = conn.isValid(3);
                return new TestResult(valid, valid ? "连接成功" : "连接校验失败",
                        System.currentTimeMillis() - t0);
            }
        } catch (SQLException ex) {
            return new TestResult(false, "连接失败: " + ex.getMessage(), System.currentTimeMillis() - t0);
        }
    }

    public record TestResult(boolean ok, String message, long latencyMs) {
    }

    public DbConnection get(Long id) {
        return requireExists(id);
    }

    public List<DbConnection> list() {
        return mapper.selectAll();
    }

    private DbConnection requireExists(Long id) {
        DbConnection e = mapper.selectById(id);
        if (e == null) {
            throw new BizException(ErrorCode.DS_NOT_FOUND, "连接不存在: " + id);
        }
        return e;
    }
}

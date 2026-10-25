package com.sqlpipeline.datasource.service;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.datasource.config.DatasourceProperties;
import com.sqlpipeline.datasource.crypto.CryptoService;
import com.sqlpipeline.datasource.entity.DbConnection;
import com.sqlpipeline.datasource.mapper.DbConnectionMapper;
import com.sqlpipeline.datasource.registry.DataSourceRegistry;
import com.sqlpipeline.datasource.registry.PoolBudgetValidator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.springframework.util.StringUtils.hasText;

/** 连接管理：CRUD + 凭据加密 + 连接池热更新 + 连通性测试（含只读账号探测）。 */
@Service
@RequiredArgsConstructor
public class ConnectionService {

    private final DbConnectionMapper mapper;
    private final CryptoService crypto;
    private final DataSourceRegistry registry;
    private final MessageSource messageSource;
    private final PoolBudgetValidator poolBudgetValidator;
    private final DatasourceProperties datasourceProperties;

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }

    public Long create(DbConnection entity, String plainPassword, String operator) {
        if (!hasText(entity.getConnKey())) {
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.sys.connKeyRequired");
        }
        if (!hasText(plainPassword)) {
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.sys.passwordRequired");
        }
        if (mapper.selectByKey(entity.getConnKey()) != null) {
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.ds.connKeyExists", entity.getConnKey());
        }
        entity.setPasswordEnc(crypto.encrypt(plainPassword));
        if (entity.getEnabled() == null) {
            entity.setEnabled(1);
        }
        if (!hasText(entity.getDriverClass())) {
            entity.setDriverClass("org.postgresql.Driver");
        }
        // INSERT 显式列出这些列，这里补默认值避免写入 NULL
        if (entity.getPoolSize() == null) entity.setPoolSize(datasourceProperties.getDefaultPoolSize());
        if (entity.getConnTimeoutMs() == null) entity.setConnTimeoutMs(5000);
        if (entity.getMaxRows() == null) entity.setMaxRows(1000);
        if (entity.getQueryTimeoutS() == null) entity.setQueryTimeoutS(30);
        // P0：同一目标库（规范化 jdbc_url 归组）的池容量总预算校验，超限 DS0004
        poolBudgetValidator.validate(entity.getJdbcUrl(), entity.getPoolSize(), null);
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
        if (patch.getDefaultSchema() != null) {
            // 传空串表示清空 schema（回到驱动默认 search_path）
            String schema = patch.getDefaultSchema().trim();
            old.setDefaultSchema(schema.isEmpty() ? null : schema);
        }
        if (hasText(plainPassword)) {
            old.setPasswordEnc(crypto.encrypt(plainPassword));
        }
        // P0：池容量预算校验（更新后生效的 url + pool_size，排除自身旧值）
        poolBudgetValidator.validate(old.getJdbcUrl(), old.getPoolSize(), old.getId());
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
        if (hasText(e.getDefaultSchema())) {
            cfg.setSchema(e.getDefaultSchema().trim());
        }
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(e.getConnTimeoutMs() != null ? e.getConnTimeoutMs() : 5000);
        cfg.setValidationTimeout(5000);
        cfg.setPoolName("conn-test-" + e.getConnKey());
        cfg.setInitializationFailTimeout(-1);
        try (HikariDataSource ds = new HikariDataSource(cfg)) {
            try (Connection conn = ds.getConnection()) {
                boolean valid = conn.isValid(3);
                String message;
                if (valid) {
                    message = msg("error.ds.testOk", System.currentTimeMillis() - t0);
                    // P0 只读账号探测（L4 提示）：pg_roles 检查该账号是否为超级用户/可建对象，
                    // 建议健康检查使用仅 GRANT SELECT 的专用只读角色（配合执行侧 BEGIN READ ONLY 双保险）
                    String hint = probeReadOnlyRole(conn, e);
                    if (hint != null) {
                        message = message + " · " + hint;
                    }
                } else {
                    message = msg("error.ds.testInvalid");
                }
                return new TestResult(valid, message, System.currentTimeMillis() - t0);
            }
        } catch (SQLException ex) {
            return new TestResult(false, msg("error.ds.testFailed", ex.getMessage()),
                    System.currentTimeMillis() - t0);
        }
    }

    /**
     * 探测连接账号的角色权限（仅 PostgreSQL）：rolsuper / rolcreaterole / rolcreatedb / rolreplication
     * 任一为真即提示改用只读账号；非 PG 或查询失败返回 null（探测不影响连通性结论）。
     */
    private String probeReadOnlyRole(Connection conn, DbConnection e) {
        if (e.getDriverClass() == null || !e.getDriverClass().contains("postgresql")) {
            return null;
        }
        String sql = "SELECT rolsuper, rolcreaterole, rolcreatedb, rolreplication "
                + "FROM pg_roles WHERE rolname = current_user";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                boolean privileged = rs.getBoolean(1) || rs.getBoolean(2)
                        || rs.getBoolean(3) || rs.getBoolean(4);
                return privileged ? msg("error.ds.privilegedHint", e.getUsername()) : null;
            }
        } catch (SQLException ignore) {
            // pg_roles 探测失败不影响连通性结论
        }
        return null;
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
            throw BizException.i18n(ErrorCode.DS_NOT_FOUND, "error.ds.notFound", id);
        }
        return e;
    }
}

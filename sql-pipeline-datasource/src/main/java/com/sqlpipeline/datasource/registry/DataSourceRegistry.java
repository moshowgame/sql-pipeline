package com.sqlpipeline.datasource.registry;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.datasource.crypto.CryptoService;
import com.sqlpipeline.datasource.entity.DbConnection;
import com.sqlpipeline.datasource.mapper.DbConnectionMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;
import javax.sql.DataSource;

import static org.springframework.util.StringUtils.hasText;

/**
 * 数据源注册与路由：按 connKey 管理 Hikari 连接池，支持运行期热更新。
 * 不使用 AbstractRoutingDataSource + ThreadLocal（ADR-02），避免线程池复用导致的上下文串号。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSourceRegistry implements DisposableBean {

    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final DbConnectionMapper mapper;
    private final CryptoService crypto;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    @PostConstruct
    public void init() {
        mapper.selectEnabled().forEach(this::register);
        log.info("数据源注册完成: {}", pools.keySet());
    }

    public void register(DbConnection e) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("pool-" + e.getConnKey());
        cfg.setJdbcUrl(e.getJdbcUrl());
        cfg.setUsername(e.getUsername());
        cfg.setPassword(crypto.decrypt(e.getPasswordEnc()));
        cfg.setDriverClassName(e.getDriverClass());
        // 连接默认 Schema：Hikari 会在连接建立时设置 search_path
        if (hasText(e.getDefaultSchema())) {
            cfg.setSchema(e.getDefaultSchema().trim());
        }
        cfg.setMaximumPoolSize(e.getPoolSize() != null ? e.getPoolSize() : 10);
        cfg.setConnectionTimeout(e.getConnTimeoutMs() != null ? e.getConnTimeoutMs() : 5000);
        cfg.setAutoCommit(true);
        // 目标库启动时可能暂不可用，不允许阻塞平台启动
        cfg.setInitializationFailTimeout(-1);
        HikariDataSource ds = new HikariDataSource(cfg);
        HikariDataSource old = pools.put(e.getConnKey(), ds);
        if (old != null) {
            old.close();
        }
        bindMetrics(ds, e.getConnKey());
        log.info("数据源已注册: connKey={}, poolSize={}", e.getConnKey(), cfg.getMaximumPoolSize());
    }

    public DataSource get(String key) {
        HikariDataSource ds = pools.get(key);
        if (ds == null) {
            throw new BizException(ErrorCode.DS_NOT_FOUND, "数据源不存在或未启用: " + key);
        }
        return ds;
    }

    /** 热更新：先移除再关闭，防止并发请求拿到正在关闭的池；已禁用的连接不重建。 */
    public synchronized void reload(String key) {
        HikariDataSource old = pools.remove(key);
        if (old != null) {
            old.close();
        }
        DbConnection e = mapper.selectByKey(key);
        if (e != null && e.getEnabled() == 1) {
            register(e);
        } else {
            log.info("数据源已移除: connKey={}", key);
        }
    }

    @Override
    public void destroy() {
        pools.values().forEach(HikariDataSource::close);
    }

    private void bindMetrics(HikariDataSource ds, String connKey) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry == null) {
            return;
        }
        Gauge.builder("db_pool_active", ds, poolValue(HikariPoolMXBean::getActiveConnections))
                .tag("conn_key", connKey).description("活跃连接数").register(registry);
        Gauge.builder("db_pool_idle", ds, poolValue(HikariPoolMXBean::getIdleConnections))
                .tag("conn_key", connKey).description("空闲连接数").register(registry);
        Gauge.builder("db_pool_pending", ds, poolValue(HikariPoolMXBean::getThreadsAwaitingConnection))
                .tag("conn_key", connKey).description("等待连接的线程数").register(registry);
    }

    private ToDoubleFunction<HikariDataSource> poolValue(ToDoubleFunction<HikariPoolMXBean> fn) {
        return ds -> {
            HikariPoolMXBean mx = ds.getHikariPoolMXBean();
            return mx == null ? 0 : fn.applyAsDouble(mx);
        };
    }
}

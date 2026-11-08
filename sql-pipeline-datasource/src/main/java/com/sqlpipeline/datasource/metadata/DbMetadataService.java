package com.sqlpipeline.datasource.metadata;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.SqlExecException;
import com.sqlpipeline.datasource.registry.DataSourceRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/**
 * 数据库表/字段元数据服务（SQL 编辑器补全数据源）：
 * - 查询 information_schema（排除系统 schema），按 connKey 缓存 5 分钟
 * - 表数量上限 MAX_ROWS 防御超大库（列数截断标记，仅影响补全覆盖面，无功能影响）
 */
@Service
@RequiredArgsConstructor
public class DbMetadataService {

    private static final long TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_ROWS = 20000;

    private static final String METADATA_SQL =
            "SELECT c.table_name, c.column_name " +
            "FROM information_schema.columns c " +
            "JOIN information_schema.tables t " +
            "  ON t.table_schema = c.table_schema AND t.table_name = c.table_name " +
            "WHERE c.table_schema NOT IN ('pg_catalog', 'information_schema') " +
            "  AND c.table_schema NOT LIKE 'pg\\_toast%' " +
            "  AND t.table_type IN ('BASE TABLE', 'VIEW') " +
            "ORDER BY c.table_name, c.ordinal_position " +
            "LIMIT " + MAX_ROWS;

    private final DataSourceRegistry registry;

    /** 表元数据：表名 + 有序列列表（补全提示词）。 */
    public record TableMeta(String name, List<String> columns) {
    }

    private record CacheEntry(long loadedAt, List<TableMeta> tables) {
    }

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public List<TableMeta> tables(String connKey) {
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(connKey);
        if (entry != null && now - entry.loadedAt() < TTL_MS) {
            return entry.tables();
        }
        List<TableMeta> tables = load(connKey);
        cache.put(connKey, new CacheEntry(now, tables));
        return tables;
    }

    /** 连接变更（编辑/重载/删除）后主动失效。 */
    public void evict(String connKey) {
        if (connKey != null) {
            cache.remove(connKey);
        }
    }

    private List<TableMeta> load(String connKey) {
        DataSource ds = registry.get(connKey);
        Map<String, List<String>> byTable = new java.util.LinkedHashMap<>();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(METADATA_SQL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byTable.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                        .add(rs.getString(2));
            }
        } catch (SQLException e) {
            throw new SqlExecException(ErrorCode.DS_CONNECT_FAILED,
                    "error.ds.metadataFailed", new Object[]{e.getMessage()}, e);
        }
        List<TableMeta> tables = new ArrayList<>(byTable.size());
        byTable.forEach((name, cols) -> tables.add(new TableMeta(name, cols)));
        return tables;
    }
}

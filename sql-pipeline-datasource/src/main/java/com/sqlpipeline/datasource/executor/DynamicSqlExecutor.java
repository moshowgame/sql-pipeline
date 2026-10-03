package com.sqlpipeline.datasource.executor;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.SqlExecException;
import com.sqlpipeline.datasource.registry.DataSourceRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * 动态 SQL 执行器：业务 SQL 走原生 JDBC（ADR-01），不经过 MyBatis，
 * 数据源按 connKey 显式传递（ADR-02），不使用 ThreadLocal 路由。
 */
@Component
@RequiredArgsConstructor
public class DynamicSqlExecutor {

    private final DataSourceRegistry registry;

    /** 健康检查用：只读查询，限制行数与超时。 */
    public QueryResult query(String connKey, String sql, List<Object> params, int maxRows, int timeoutSec) {
        DataSource ds = registry.get(connKey);
        try (Connection conn = ds.getConnection()) {
            // L3 兜底：PostgreSQL 驱动会向服务端发送 SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY
            conn.setReadOnly(true);
            conn.setAutoCommit(true);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(Math.max(1, timeoutSec));
                ps.setMaxRows(Math.max(1, maxRows) + 1); // 多读一行用于判断是否截断
                bindParams(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    return readResultSet(rs, maxRows);
                }
            }
        } catch (SQLTimeoutException e) {
            throw new SqlExecException(ErrorCode.HC_SQL_TIMEOUT,
                    "error.hc.sqlTimeout", new Object[]{timeoutSec}, e);
        } catch (SQLException e) {
            throw new SqlExecException(ErrorCode.HC_SQL_ERROR,
                    "error.hc.sqlError", new Object[]{e.getMessage()}, e);
        }
    }

    private void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    private QueryResult readResultSet(ResultSet rs, int maxRows) throws SQLException {
        QueryResult result = new QueryResult();
        ResultSetMetaData md = rs.getMetaData();
        int colCount = md.getColumnCount();
        for (int i = 1; i <= colCount; i++) {
            result.getColumns().add(md.getColumnLabel(i));
        }
        while (rs.next()) {
            if (result.getRows().size() >= maxRows) {
                result.setTruncated(true);
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= colCount; i++) {
                row.put(md.getColumnLabel(i), convert(rs.getObject(i)));
            }
            result.getRows().add(row);
        }
        result.setRowCount(result.getRows().size());
        return result;
    }

    /** 将 JDBC 返回值转为 JSON 友好类型。 */
    private Object convert(Object v) throws SQLException {
        if (v == null) {
            return null;
        }
        if (v instanceof Timestamp ts) {
            return ts.toInstant().toString();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof Time t) {
            return t.toLocalTime().toString();
        }
        if (v instanceof OffsetDateTime odt) {
            return odt.toString();
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt.toString();
        }
        if (v instanceof LocalDate ld) {
            return ld.toString();
        }
        if (v instanceof Array arr) {
            Object[] elements = (Object[]) arr.getArray();
            Object[] converted = new Object[elements.length];
            for (int i = 0; i < elements.length; i++) {
                converted[i] = convert(elements[i]);
            }
            arr.free();
            return List.of(converted);
        }
        if (v instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (v instanceof BigDecimal || v instanceof Number || v instanceof Boolean || v instanceof String) {
            return v;
        }
        // PGobject（jsonb/uuid 等）、PGInterval 等驱动特定类型
        return v.toString();
    }
}

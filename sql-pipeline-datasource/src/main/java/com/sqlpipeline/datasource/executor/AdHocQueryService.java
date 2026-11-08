package com.sqlpipeline.datasource.executor;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.datasource.entity.DbConnection;
import com.sqlpipeline.datasource.guard.SqlGuard;
import com.sqlpipeline.datasource.guard.SqlSplitter;
import com.sqlpipeline.datasource.mapper.DbConnectionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 即席查询服务（编辑器"试运行"/后续 SQL Console）：
 * - L2 拦截：SqlGuard AST 校验 SELECT-only（fail-closed），复用 L3 只读事务兜底
 * - 多语句：SqlSplitter 拆分逐条执行，语句数上限 MAX_STATEMENTS 防御误粘贴超大脚本
 * - 资源约束：maxRows / 超时取连接配置（连接级 queryTimeoutS 优先），与健康检查同一条执行链路
 */
@Service
@RequiredArgsConstructor
public class AdHocQueryService {

    private static final int MAX_STATEMENTS = 20;
    private static final int DEFAULT_MAX_ROWS = 200;
    private static final int MAX_ROWS_LIMIT = 1000;
    private static final int DEFAULT_TIMEOUT_SEC = 30;
    private static final int MAX_TIMEOUT_SEC = 120;

    private final DbConnectionMapper connectionMapper;
    private final SqlGuard sqlGuard;
    private final DynamicSqlExecutor executor;

    /** 多语句只读查询，按拆分顺序返回结果集。 */
    public List<QueryResult> run(String connKey, String sql, Integer maxRows, Integer timeoutSec) {
        DbConnection conn = connectionMapper.selectByKey(connKey);
        if (conn == null) {
            throw BizException.i18n(ErrorCode.DS_NOT_FOUND, "error.ds.notFound", connKey);
        }
        if (conn.getEnabled() == null || conn.getEnabled() != 1) {
            throw BizException.i18n(ErrorCode.DS_DISABLED, "error.ds.disabled", connKey);
        }
        sqlGuard.assertSelectOnly(sql);
        List<String> statements = SqlSplitter.split(sql);
        if (statements.size() > MAX_STATEMENTS) {
            throw BizException.i18n(ErrorCode.DS_ADHOC_TOO_MANY, "error.ds.adhocTooMany",
                    statements.size(), MAX_STATEMENTS);
        }
        int rows = maxRows == null ? DEFAULT_MAX_ROWS
                : Math.max(1, Math.min(maxRows, MAX_ROWS_LIMIT));
        int timeout = resolveTimeout(conn, timeoutSec);
        List<QueryResult> results = new ArrayList<>(statements.size());
        for (String stmt : statements) {
            results.add(executor.query(connKey, stmt, null, rows, timeout));
        }
        return results;
    }

    private int resolveTimeout(DbConnection conn, Integer timeoutSec) {
        Integer configured = conn.getQueryTimeoutS();
        int base = timeoutSec != null ? timeoutSec
                : (configured != null && configured > 0 ? configured : DEFAULT_TIMEOUT_SEC);
        return Math.max(1, Math.min(base, MAX_TIMEOUT_SEC));
    }
}

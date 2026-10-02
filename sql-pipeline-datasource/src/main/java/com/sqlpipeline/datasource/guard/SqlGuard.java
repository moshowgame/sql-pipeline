package com.sqlpipeline.datasource.guard;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.SqlGuardException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SQL 只读校验（L1 保存时 / L2 执行前），基于 JSqlParser AST，解析失败一律拒绝（fail-closed）。
 */
@Component
public class SqlGuard {

    public void assertSelectOnly(String rawSql) {
        List<String> statements = SqlSplitter.split(rawSql);
        if (statements.isEmpty()) {
            throw new SqlGuardException(ErrorCode.SG_PARSE_FAILED, "SQL 为空");
        }
        for (String stmt : statements) {
            assertSingleSelectOnly(stmt);
        }
    }

    private void assertSingleSelectOnly(String stmt) {
        Statement parsed;
        try {
            parsed = CCJSqlParserUtil.parse(stmt);
        } catch (JSQLParserException e) {
            throw new SqlGuardException(ErrorCode.SG_PARSE_FAILED,
                    "SQL 解析失败，拒绝执行: " + rootMessage(e));
        }
        if (!(parsed instanceof Select select)) {
            throw new SqlGuardException(ErrorCode.SG_NON_SELECT,
                    "只允许 SELECT，检测到: " + parsed.getClass().getSimpleName());
        }
        assertNoForbiddenClause(select);
    }

    private void assertNoForbiddenClause(Select select) {
        String s = select.toString().toLowerCase();
        if (s.contains("for update")) {
            throw new SqlGuardException(ErrorCode.SG_FORBIDDEN_CLAUSE, "不允许 FOR UPDATE");
        }
        if (s.contains("into outfile")) {
            throw new SqlGuardException(ErrorCode.SG_FORBIDDEN_CLAUSE, "不允许 INTO OUTFILE");
        }
        if (s.contains("into dumpfile")) {
            throw new SqlGuardException(ErrorCode.SG_FORBIDDEN_CLAUSE, "不允许 INTO DUMPFILE");
        }
        if (select instanceof PlainSelect ps) {
            var intoTables = ps.getIntoTables();
            if (intoTables != null && !intoTables.isEmpty()) {
                throw new SqlGuardException(ErrorCode.SG_FORBIDDEN_CLAUSE,
                        "不允许 SELECT ... INTO（PostgreSQL 中会创建表）");
            }
        }
    }

    private String rootMessage(JSQLParserException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String msg = cause.getMessage();
        if (msg != null && msg.length() > 200) {
            msg = msg.substring(0, 200);
        }
        return msg;
    }
}

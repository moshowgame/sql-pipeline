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
            throw SqlGuardException.i18n(ErrorCode.SG_PARSE_FAILED, "error.sg.empty");
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
            throw SqlGuardException.i18n(ErrorCode.SG_PARSE_FAILED,
                    "error.sg.parseFailed", rootMessage(e));
        }
        if (!(parsed instanceof Select select)) {
            throw SqlGuardException.i18n(ErrorCode.SG_NON_SELECT,
                    "error.sg.nonSelect", parsed.getClass().getSimpleName());
        }
        assertNoForbiddenClause(select);
    }

    private void assertNoForbiddenClause(Select select) {
        String s = select.toString().toLowerCase();
        if (s.contains("for update")) {
            throw SqlGuardException.i18n(ErrorCode.SG_FORBIDDEN_CLAUSE, "error.sg.forUpdate");
        }
        if (s.contains("into outfile")) {
            throw SqlGuardException.i18n(ErrorCode.SG_FORBIDDEN_CLAUSE, "error.sg.intoOutfile");
        }
        if (s.contains("into dumpfile")) {
            throw SqlGuardException.i18n(ErrorCode.SG_FORBIDDEN_CLAUSE, "error.sg.intoDumpfile");
        }
        if (select instanceof PlainSelect ps) {
            var intoTables = ps.getIntoTables();
            if (intoTables != null && !intoTables.isEmpty()) {
                throw SqlGuardException.i18n(ErrorCode.SG_FORBIDDEN_CLAUSE, "error.sg.selectInto");
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

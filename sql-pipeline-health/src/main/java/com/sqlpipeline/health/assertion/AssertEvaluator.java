package com.sqlpipeline.health.assertion;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.datasource.executor.QueryResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 断言求值器：VALUE / ROWCOUNT / RECORD 三类断言。 */
@Component
public class AssertEvaluator {

    private static final Pattern CELL = Pattern.compile("^cell\\((\\d+)\\s*,\\s*(\\d+)\\)$");

    public AssertResult evaluate(AssertConfig cfg, QueryResult result) {
        return switch (cfg.getType()) {
            case AssertConfig.TYPE_VALUE -> evalValue(cfg, result);
            case AssertConfig.TYPE_ROWCOUNT -> evalRowCount(cfg, result);
            case AssertConfig.TYPE_RECORD -> evalRecord(cfg, result);
            default -> throw new BizException(ErrorCode.HC_CONFIG_INVALID, "未知断言类型: " + cfg.getType());
        };
    }

    private AssertResult evalValue(AssertConfig cfg, QueryResult r) {
        Object actual;
        if ("rowCount".equals(cfg.getExpr())) {
            actual = r.getRowCount();
        } else {
            Matcher m = CELL.matcher(cfg.getExpr().trim());
            if (!m.matches()) {
                throw new BizException(ErrorCode.HC_CONFIG_INVALID,
                        "expr 仅支持 rowCount 或 cell(row,col)，当前: " + cfg.getExpr());
            }
            actual = r.cell(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }
        boolean pass = Operator.apply(cfg.getOp(), actual, cfg.getValue());
        return new AssertResult(pass, String.format("expected %s %s, actual=%s",
                cfg.getOp(), cfg.getValue(), actual));
    }

    private AssertResult evalRowCount(AssertConfig cfg, QueryResult r) {
        Integer actual = r.getRowCount();
        boolean pass = Operator.apply(cfg.getOp(), actual, cfg.getValue());
        return new AssertResult(pass, String.format("expected rowCount %s %s, actual=%d%s",
                cfg.getOp(), cfg.getValue(), actual, r.isTruncated() ? "（结果集已截断）" : ""));
    }

    private AssertResult evalRecord(AssertConfig cfg, QueryResult r) {
        if (r.getRows().isEmpty()) {
            return new AssertResult(false, "无记录可校验");
        }
        boolean all = "ALL".equalsIgnoreCase(cfg.getMode());
        if (all) {
            for (int i = 0; i < r.getRows().size(); i++) {
                Map<String, Object> row = r.getRows().get(i);
                for (AssertConfig.Rule rule : cfg.getRules()) {
                    if (!Operator.apply(rule.getOp(), row.get(rule.getField()), rule.getValue())) {
                        return new AssertResult(false, String.format("第 %d 行不满足规则: field=%s %s %s (actual=%s)",
                                i, rule.getField(), rule.getOp(), rule.getValue(), row.get(rule.getField())));
                    }
                }
            }
            return new AssertResult(true, String.format("全部 %d 行满足 %d 条规则", r.getRows().size(), cfg.getRules().size()));
        }
        for (Map<String, Object> row : r.getRows()) {
            boolean rowPass = true;
            for (AssertConfig.Rule rule : cfg.getRules()) {
                if (!Operator.apply(rule.getOp(), row.get(rule.getField()), rule.getValue())) {
                    rowPass = false;
                    break;
                }
            }
            if (rowPass) {
                return new AssertResult(true, "存在满足全部规则的记录");
            }
        }
        return new AssertResult(false, "无任一记录满足全部规则");
    }
}

package com.sqlpipeline.health.assertion;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.datasource.executor.QueryResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 断言求值器（简化模型）：断言 = 目标类型 + 操作符 + 期望数值。
 * <ul>
 *   <li>VALUE（数值）：取查询结果第一行第一列，与期望值按操作符比较</li>
 *   <li>ROWS（数组/行数）：取查询结果行数，与期望值按操作符比较</li>
 * </ul>
 * 操作符：== != > >= < <=。不引入表达式引擎（ADR-08）。
 */
@Component
public class AssertEvaluator {

    public static final String TYPE_VALUE = "VALUE";
    public static final String TYPE_ROWS = "ROWS";

    public static final Set<String> TYPES = Set.of(TYPE_VALUE, TYPE_ROWS);
    public static final Set<String> OPS = Set.of("==", "!=", ">", ">=", "<", "<=");

    public AssertResult evaluate(String type, String op, BigDecimal expected, QueryResult result) {
        checkOp(op);
        Object actual = TYPE_ROWS.equals(type)
                ? BigDecimal.valueOf(result.getRowCount())
                : result.cell(0, 0);

        BigDecimal actualNum = toNumber(actual);
        boolean pass = actualNum != null && compare(op, actualNum, expected);
        String message = String.format("expected %s %s, actual=%s%s",
                op, expected, actual, actualNum == null && actual != null ? "（非数值）" : "");
        return new AssertResult(pass, message);
    }

    /** 保存前校验断言三要素。 */
    public void validate(String type, String op, BigDecimal value) {
        if (type == null || !TYPES.contains(type)) {
            throw new BizException(ErrorCode.HC_CONFIG_INVALID, "断言类型仅支持 VALUE（返回值）/ ROWS（返回行数）: " + type);
        }
        checkOp(op);
        if (value == null) {
            throw new BizException(ErrorCode.HC_CONFIG_INVALID, "断言缺少期望值");
        }
    }

    private void checkOp(String op) {
        if (op == null || !OPS.contains(op)) {
            throw new BizException(ErrorCode.HC_CONFIG_INVALID, "不支持的操作符: " + op + "，支持 == != > >= < <=");
        }
    }

    private boolean compare(String op, BigDecimal actual, BigDecimal expected) {
        int c = actual.compareTo(expected);
        return switch (op) {
            case "==" -> c == 0;
            case "!=" -> c != 0;
            case ">" -> c > 0;
            case ">=" -> c >= 0;
            case "<" -> c < 0;
            case "<=" -> c <= 0;
            default -> throw new BizException(ErrorCode.HC_CONFIG_INVALID, "不支持的操作符: " + op);
        };
    }

    private BigDecimal toNumber(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (v instanceof Boolean b) {
            return b ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

package com.sqlpipeline.health.assertion;

import com.fasterxml.jackson.databind.JsonNode;
import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;

import java.math.BigDecimal;

/**
 * 断言操作符表（白名单实现，ADR-08：不走 SpEL，避免表达式注入）。
 * 数值优先按 BigDecimal 比较，否则退化为字符串比较；实际值取自查询结果。
 */
public final class Operator {

    private Operator() {
    }

    public static boolean apply(String op, Object actual, JsonNode expected) {
        return switch (op) {
            case "not_null" -> actual != null;
            case "is_null" -> actual == null;
            case "==" -> compare(actual, expected) == 0;
            case "!=" -> compare(actual, expected) != 0;
            case ">" -> compare(actual, expected) > 0;
            case ">=" -> compare(actual, expected) >= 0;
            case "<" -> compare(actual, expected) < 0;
            case "<=" -> compare(actual, expected) <= 0;
            case "in" -> inList(actual, expected, true);
            case "not_in" -> inList(actual, expected, false);
            case "regex" -> actual != null && actual.toString().matches(expected.asText());
            default -> throw new BizException(ErrorCode.HC_CONFIG_INVALID, "不支持的操作符: " + op);
        };
    }

    static int compare(Object actual, JsonNode expected) {
        if (actual == null) {
            return expected == null || expected.isNull() ? 0 : -1;
        }
        if (expected == null || expected.isNull()) {
            return 1;
        }
        BigDecimal a = toNumber(actual);
        // 字符串形式的数字（如 "10"）也要参与数值比较，不能依赖 JsonNode.decimalValue()（TextNode 会返回 0）
        BigDecimal b = expected.isNumber() ? expected.decimalValue()
                : expected.isTextual() ? toNumber(expected.asText()) : null;
        if (a != null && b != null) {
            return a.compareTo(b);
        }
        return actual.toString().compareTo(expected.asText());
    }

    private static boolean inList(Object actual, JsonNode expected, boolean wantIn) {
        if (expected == null || !expected.isArray()) {
            throw new BizException(ErrorCode.HC_CONFIG_INVALID, "操作符 in/not_in 的 value 必须是数组");
        }
        for (JsonNode element : expected) {
            if (compare(actual, element) == 0) {
                return wantIn;
            }
        }
        return !wantIn;
    }

    private static BigDecimal toNumber(Object v) {
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

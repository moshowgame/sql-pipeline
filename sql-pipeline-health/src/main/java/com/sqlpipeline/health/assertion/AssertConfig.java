package com.sqlpipeline.health.assertion;

import com.fasterxml.jackson.databind.JsonNode;
import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.springframework.util.StringUtils.hasText;

/**
 * 断言配置（对应 sql_definition.assert_config，JSONB 存储）。
 * <pre>
 * VALUE:    {"type":"VALUE","expr":"cell(0,0)","op":"==","value":1}
 * ROWCOUNT: {"type":"ROWCOUNT","op":">=","value":1}
 * RECORD:   {"type":"RECORD","mode":"ALL","rules":[{"field":"status","op":"not_null"}]}
 * </pre>
 */
@Data
public class AssertConfig {

    public static final String TYPE_VALUE = "VALUE";
    public static final String TYPE_ROWCOUNT = "ROWCOUNT";
    public static final String TYPE_RECORD = "RECORD";

    private static final Set<String> TYPES = Set.of(TYPE_VALUE, TYPE_ROWCOUNT, TYPE_RECORD);
    /** 需要 value 的操作符。 */
    private static final Set<String> OPS_WITH_VALUE =
            Set.of("==", "!=", ">", ">=", "<", "<=", "in", "not_in", "regex");
    private static final Set<String> OPS = Set.of("==", "!=", ">", ">=", "<", "<=",
            "not_null", "is_null", "in", "not_in", "regex");

    private String type;
    /** VALUE 断言取值表达式：rowCount 或 cell(row,col)。 */
    private String expr;
    private String op;
    private JsonNode value;
    /** RECORD 断言模式：ALL | ANY。 */
    private String mode;
    private List<Rule> rules = new ArrayList<>();

    @Data
    public static class Rule {
        private String field;
        private String op;
        private JsonNode value;
    }

    /** 保存前校验，非法配置抛 HC0002。 */
    public void validate() {
        if (type == null || !TYPES.contains(type)) {
            throw new BizException(ErrorCode.HC_CONFIG_INVALID, "未知断言类型: " + type + "，支持 VALUE/ROWCOUNT/RECORD");
        }
        switch (type) {
            case TYPE_VALUE -> {
                require(expr, "VALUE 断言缺少 expr");
                checkOp(op);
            }
            case TYPE_ROWCOUNT -> checkOp(op);
            case TYPE_RECORD -> {
                if (!"ALL".equalsIgnoreCase(mode) && !"ANY".equalsIgnoreCase(mode)) {
                    throw new BizException(ErrorCode.HC_CONFIG_INVALID, "RECORD 断言 mode 必须为 ALL 或 ANY");
                }
                if (rules == null || rules.isEmpty()) {
                    throw new BizException(ErrorCode.HC_CONFIG_INVALID, "RECORD 断言缺少 rules");
                }
                for (Rule rule : rules) {
                    require(rule.getField(), "规则缺少 field");
                    if (!OPS.contains(rule.getOp())) {
                        throw new BizException(ErrorCode.HC_CONFIG_INVALID, "不支持的操作符: " + rule.getOp());
                    }
                    if (OPS_WITH_VALUE.contains(rule.getOp()) && (rule.getValue() == null || rule.getValue().isNull())) {
                        throw new BizException(ErrorCode.HC_CONFIG_INVALID, "操作符 " + rule.getOp() + " 需要 value");
                    }
                }
            }
            default -> throw new BizException(ErrorCode.HC_CONFIG_INVALID, "未知断言类型: " + type);
        }
    }

    private void checkOp(String op) {
        if (!OPS.contains(op)) {
            throw new BizException(ErrorCode.HC_CONFIG_INVALID, "不支持的操作符: " + op);
        }
        if (OPS_WITH_VALUE.contains(op) && (value == null || value.isNull())) {
            throw new BizException(ErrorCode.HC_CONFIG_INVALID, "操作符 " + op + " 需要 value");
        }
    }

    private void require(String v, String msg) {
        if (!hasText(v)) {
            throw new BizException(ErrorCode.HC_CONFIG_INVALID, msg);
        }
    }
}

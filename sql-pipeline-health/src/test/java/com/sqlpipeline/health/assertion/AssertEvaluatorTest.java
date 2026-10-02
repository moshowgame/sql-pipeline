package com.sqlpipeline.health.assertion;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.common.util.JsonUtils;
import com.sqlpipeline.datasource.executor.QueryResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssertEvaluatorTest {

    private final AssertEvaluator evaluator = new AssertEvaluator();

    private AssertConfig config(String json) {
        AssertConfig cfg = JsonUtils.fromJson(json, AssertConfig.class, ErrorCode.HC_CONFIG_INVALID);
        if (cfg != null) {
            cfg.validate();
        }
        return cfg;
    }

    private QueryResult oneRowResult(Object firstCellValue) {
        QueryResult result = new QueryResult();
        result.getColumns().add("cnt");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("cnt", firstCellValue);
        result.getRows().add(row);
        result.setRowCount(1);
        return result;
    }

    @Test
    void valueCellEquals() {
        QueryResult result = oneRowResult(5);
        assertThat(evaluator.evaluate(config(
                "{\"type\":\"VALUE\",\"expr\":\"cell(0,0)\",\"op\":\"==\",\"value\":5}"), result).isPass()).isTrue();
        assertThat(evaluator.evaluate(config(
                "{\"type\":\"VALUE\",\"expr\":\"cell(0,0)\",\"op\":\"==\",\"value\":6}"), result).isPass()).isFalse();
    }

    @Test
    void valueCellGte() {
        assertThat(evaluator.evaluate(config(
                "{\"type\":\"VALUE\",\"expr\":\"cell(0,0)\",\"op\":\">=\",\"value\":5}"), oneRowResult(5)).isPass()).isTrue();
        assertThat(evaluator.evaluate(config(
                "{\"type\":\"VALUE\",\"expr\":\"cell(0,0)\",\"op\":\"<\",\"value\":\"10\"}"), oneRowResult(9)).isPass()).isTrue();
    }

    @Test
    void valueRowCountExpr() {
        QueryResult result = oneRowResult(1);
        result.setRowCount(3);
        assertThat(evaluator.evaluate(config(
                "{\"type\":\"VALUE\",\"expr\":\"rowCount\",\"op\":\">=\",\"value\":1}"), result).isPass()).isTrue();
    }

    @Test
    void rowCountAssert() {
        QueryResult result = oneRowResult(1);
        assertThat(evaluator.evaluate(config(
                "{\"type\":\"ROWCOUNT\",\"op\":\">=\",\"value\":1}"), result).isPass()).isTrue();
        assertThat(evaluator.evaluate(config(
                "{\"type\":\"ROWCOUNT\",\"op\":\"==\",\"value\":2}"), result).isPass()).isFalse();
    }

    @Test
    void recordAllMode() {
        QueryResult result = new QueryResult();
        result.getColumns().add("status");
        result.getColumns().add("amount");
        result.getRows().add(Map.of("status", "A", "amount", 10));
        result.getRows().add(Map.of("status", "B", "amount", 0));
        result.setRowCount(2);
        boolean pass = evaluator.evaluate(config("""
                {"type":"RECORD","mode":"ALL","rules":[
                  {"field":"status","op":"in","value":["A","B"]},
                  {"field":"amount","op":"not_null"}
                ]}"""), result).isPass();
        assertThat(pass).isTrue();

        boolean fail = evaluator.evaluate(config("""
                {"type":"RECORD","mode":"ALL","rules":[
                  {"field":"amount","op":">","value":0}
                ]}"""), result).isPass();
        assertThat(fail).isFalse();
    }

    @Test
    void recordAnyMode() {
        QueryResult result = new QueryResult();
        result.getColumns().add("status");
        result.getRows().add(Map.of("status", "X"));
        result.getRows().add(Map.of("status", "A"));
        result.setRowCount(2);
        assertThat(evaluator.evaluate(config("""
                {"type":"RECORD","mode":"ANY","rules":[{"field":"status","op":"in","value":["A","B"]}]}
                """), result).isPass()).isTrue();
    }

    @Test
    void recordOnEmptyRowsFails() {
        QueryResult result = new QueryResult();
        assertThat(evaluator.evaluate(config(
                "{\"type\":\"RECORD\",\"mode\":\"ALL\",\"rules\":[{\"field\":\"a\",\"op\":\"not_null\"}]}"),
                result).isPass()).isFalse();
    }

    @Test
    void regexAndNullChecks() {
        QueryResult result = oneRowResult("abc123");
        assertThat(evaluator.evaluate(config(
                "{\"type\":\"VALUE\",\"expr\":\"cell(0,0)\",\"op\":\"regex\",\"value\":\"[a-z]+\\\\d+\"}"),
                result).isPass()).isTrue();
        assertThat(evaluator.evaluate(config(
                "{\"type\":\"VALUE\",\"expr\":\"cell(1,0)\",\"op\":\"is_null\",\"value\":null}"),
                result).isPass()).isTrue();
    }

    @Test
    void invalidExprRejected() {
        QueryResult result = oneRowResult(1);
        assertThatThrownBy(() -> evaluator.evaluate(config(
                "{\"type\":\"VALUE\",\"expr\":\"rows[0]\",\"op\":\"==\",\"value\":1}"), result))
                .isInstanceOf(BizException.class);
    }

    @Test
    void invalidTypeRejectedOnValidate() {
        assertThatThrownBy(() -> config("{\"type\":\"SPEL\",\"expr\":\"T(java.lang.Runtime)\"}"))
                .isInstanceOf(BizException.class);
    }
}

package com.sqlpipeline.health.assertion;

import com.sqlpipeline.datasource.executor.QueryResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssertEvaluatorTest {

    private final AssertEvaluator evaluator = new AssertEvaluator();
    private static final BigDecimal ONE = BigDecimal.ONE;

    private QueryResult oneRowResult(Object firstCellValue, int rowCount) {
        QueryResult result = new QueryResult();
        result.getColumns().add("cnt");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("cnt", firstCellValue);
        result.getRows().add(row);
        result.setRowCount(rowCount);
        return result;
    }

    @Test
    void valueEquals() {
        assertThat(evaluator.evaluate(AssertEvaluator.TYPE_VALUE, "==", ONE, oneRowResult(1, 1)).isPass()).isTrue();
        assertThat(evaluator.evaluate(AssertEvaluator.TYPE_VALUE, "==", new BigDecimal(2), oneRowResult(1, 1)).isPass()).isFalse();
    }

    @Test
    void valueGteAndLte() {
        assertThat(evaluator.evaluate(AssertEvaluator.TYPE_VALUE, ">=", new BigDecimal(5), oneRowResult(5, 1)).isPass()).isTrue();
        assertThat(evaluator.evaluate(AssertEvaluator.TYPE_VALUE, ">=", new BigDecimal(6), oneRowResult(5, 1)).isPass()).isFalse();
        assertThat(evaluator.evaluate(AssertEvaluator.TYPE_VALUE, "<=", new BigDecimal(10), oneRowResult("9", 1)).isPass()).isTrue();
    }

    @Test
    void valueNonNumericFails() {
        assertThat(evaluator.evaluate(AssertEvaluator.TYPE_VALUE, "==", ONE, oneRowResult("abc", 1)).isPass()).isFalse();
        assertThat(evaluator.evaluate(AssertEvaluator.TYPE_VALUE, "==", ONE, oneRowResult(null, 1)).isPass()).isFalse();
    }

    @Test
    void valueBooleanAsZeroOne() {
        assertThat(evaluator.evaluate(AssertEvaluator.TYPE_VALUE, "==", ONE, oneRowResult(true, 1)).isPass()).isTrue();
    }

    @Test
    void rowsCompareRowCount() {
        assertThat(evaluator.evaluate(AssertEvaluator.TYPE_ROWS, "==", BigDecimal.ZERO, oneRowResult(1, 0)).isPass()).isTrue();
        assertThat(evaluator.evaluate(AssertEvaluator.TYPE_ROWS, ">", BigDecimal.ZERO, oneRowResult(1, 3)).isPass()).isTrue();
        assertThat(evaluator.evaluate(AssertEvaluator.TYPE_ROWS, "<", new BigDecimal(5), oneRowResult(1, 3)).isPass()).isTrue();
        assertThat(evaluator.evaluate(AssertEvaluator.TYPE_ROWS, ">=", new BigDecimal(2), oneRowResult(1, 1)).isPass()).isFalse();
    }

    @Test
    void emptyResultValueFailsRowsZeroPasses() {
        QueryResult empty = new QueryResult();
        assertThat(evaluator.evaluate(AssertEvaluator.TYPE_VALUE, "==", ONE, empty).isPass()).isFalse();
        assertThat(evaluator.evaluate(AssertEvaluator.TYPE_ROWS, "==", BigDecimal.ZERO, empty).isPass()).isTrue();
    }
}

package com.sqlpipeline.datasource.executor;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlParamsTest {

    @Test
    void resolveNamedPlaceholdersInOrder() {
        String sql = "SELECT * FROM t WHERE created_at >= ${date} AND status = ${status}";
        Map<String, Object> values = new HashMap<>();
        values.put("status", 1);
        values.put("date", "2026-10-01");

        SqlParams.Resolved resolved = SqlParams.resolve(sql, values);
        assertThat(resolved.sql()).isEqualTo("SELECT * FROM t WHERE created_at >= ? AND status = ?");
        assertThat(resolved.params()).containsExactly("2026-10-01", 1);
    }

    @Test
    void samePlaceholderUsedTwice() {
        Map<String, Object> values = Map.of("a", 5);
        SqlParams.Resolved resolved = SqlParams.resolve("SELECT ${a} + ${a} FROM t", values);
        assertThat(resolved.params()).containsExactly(5, 5);
    }

    @Test
    void noPlaceholderReturnsOriginal() {
        SqlParams.Resolved resolved = SqlParams.resolve("SELECT 1", Map.of("unused", 1));
        assertThat(resolved.sql()).isEqualTo("SELECT 1");
        assertThat(resolved.params()).isEmpty();
    }

    @Test
    void missingKeyRejected() {
        assertThatThrownBy(() -> SqlParams.resolve("SELECT ${missing} FROM t", Map.of("other", 1)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.HC_CONFIG_INVALID));
    }

    @Test
    void nullValueIsAllowedWhenKeyPresent() {
        Map<String, Object> values = new HashMap<>();
        values.put("a", null);
        SqlParams.Resolved resolved = SqlParams.resolve("SELECT ${a} FROM t", values);
        assertThat(resolved.params()).containsExactly((Object) null);
    }

    @Test
    void identifierOnlyPattern() {
        // ${1abc} / ${} 不属于合法命名占位符，应保持原样
        String sql = "SELECT '${1abc}' FROM t";
        assertThat(SqlParams.hasNamedPlaceholders(sql)).isFalse();
    }
}

package com.sqlpipeline.datasource.guard;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.SqlGuardException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlGuardTest {

    private final SqlGuard guard = new SqlGuard();

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM t WHERE id = 1",
            "select count(*) from t",
            "WITH x AS (SELECT 1) SELECT * FROM x",
            "SELECT * FROM t WHERE id = $1",
            "SELECT now()",
            "SELECT 1; SELECT 2"
    })
    void allowSelectOnly(String sql) {
        assertThatCode(() -> guard.assertSelectOnly(sql)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM t",
            "UPDATE t SET a = 1",
            "INSERT INTO t VALUES (1)",
            "TRUNCATE TABLE t",
            "DROP TABLE t",
            "CREATE TABLE t (id int)",
            "ALTER TABLE t ADD COLUMN a int",
            "CALL do_something()",
            "SELECT 1; DELETE FROM t"
    })
    void rejectNonSelect(String sql) {
        assertThatThrownBy(() -> guard.assertSelectOnly(sql))
                .isInstanceOfSatisfying(SqlGuardException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SG_NON_SELECT));
    }

    @Test
    void rejectForUpdate() {
        assertThatThrownBy(() -> guard.assertSelectOnly("SELECT * FROM t WHERE id = 1 FOR UPDATE"))
                .isInstanceOfSatisfying(SqlGuardException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SG_FORBIDDEN_CLAUSE));
    }

    @Test
    void rejectSelectIntoForPostgres() {
        assertThatThrownBy(() -> guard.assertSelectOnly("SELECT * INTO new_t FROM old_t"))
                .isInstanceOfSatisfying(SqlGuardException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SG_FORBIDDEN_CLAUSE));
    }

    @Test
    void rejectUnparsableSql() {
        assertThatThrownBy(() -> guard.assertSelectOnly("SELEC * FORM t"))
                .isInstanceOfSatisfying(SqlGuardException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SG_PARSE_FAILED));
    }

    @Test
    void rejectEmptySql() {
        assertThatThrownBy(() -> guard.assertSelectOnly("  -- nothing\n"))
                .isInstanceOfSatisfying(SqlGuardException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SG_PARSE_FAILED));
    }
}

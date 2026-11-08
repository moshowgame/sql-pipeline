package com.sqlpipeline.datasource.executor;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.datasource.entity.DbConnection;
import com.sqlpipeline.datasource.guard.SqlGuard;
import com.sqlpipeline.datasource.mapper.DbConnectionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdHocQueryServiceTest {

    private DbConnectionMapper connectionMapper;
    private SqlGuard sqlGuard;
    private DynamicSqlExecutor executor;
    private AdHocQueryService service;

    @BeforeEach
    void setUp() {
        connectionMapper = mock(DbConnectionMapper.class);
        sqlGuard = mock(SqlGuard.class);
        executor = mock(DynamicSqlExecutor.class);
        service = new AdHocQueryService(connectionMapper, sqlGuard, executor);
    }

    private DbConnection conn(Integer queryTimeoutS) {
        DbConnection c = new DbConnection();
        c.setConnKey("pg-main");
        c.setEnabled(1);
        c.setQueryTimeoutS(queryTimeoutS);
        return c;
    }

    @Test
    void rejectMissingConnection() {
        when(connectionMapper.selectByKey("nope")).thenReturn(null);
        assertThatThrownBy(() -> service.run("nope", "SELECT 1", null, null))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DS_NOT_FOUND));
    }

    @Test
    void rejectDisabledConnection() {
        DbConnection c = conn(null);
        c.setEnabled(0);
        when(connectionMapper.selectByKey("pg-main")).thenReturn(c);
        assertThatThrownBy(() -> service.run("pg-main", "SELECT 1", null, null))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DS_DISABLED));
    }

    @Test
    void runMultipleStatementsInOrder() {
        when(connectionMapper.selectByKey("pg-main")).thenReturn(conn(null));
        when(executor.query(eq("pg-main"), anyString(), isNull(), anyInt(), anyInt()))
                .thenAnswer(inv -> new QueryResult());

        List<QueryResult> results = service.run("pg-main", "SELECT 1; SELECT 2", 50, null);

        assertThat(results).hasSize(2);
        verify(sqlGuard).assertSelectOnly("SELECT 1; SELECT 2");
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> rowsCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> timeoutCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(executor, times(2)).query(eq("pg-main"), sqlCaptor.capture(), isNull(),
                rowsCaptor.capture(), timeoutCaptor.capture());
        assertThat(sqlCaptor.getAllValues()).containsExactly("SELECT 1", "SELECT 2");
        assertThat(rowsCaptor.getAllValues()).containsExactly(50, 50);
        // 未显式传超时且连接未配置 → 默认 30s
        assertThat(timeoutCaptor.getAllValues()).containsExactly(30, 30);
    }

    @Test
    void rejectTooManyStatements() {
        when(connectionMapper.selectByKey("pg-main")).thenReturn(conn(null));
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 21; i++) {
            sb.append("SELECT ").append(i).append(';');
        }
        assertThatThrownBy(() -> service.run("pg-main", sb.toString(), null, null))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DS_ADHOC_TOO_MANY));
    }

    @Test
    void timeoutPrecedenceAndClamp() {
        // 显式 timeout 优先于连接配置，且被 clamp 到 120
        when(connectionMapper.selectByKey("pg-main")).thenReturn(conn(10));
        when(executor.query(eq("pg-main"), anyString(), isNull(), anyInt(), anyInt()))
                .thenAnswer(inv -> new QueryResult());

        service.run("pg-main", "SELECT 1", null, 500);
        verify(executor).query(eq("pg-main"), eq("SELECT 1"), isNull(), eq(200), eq(120));

        // 未显式传 → 连接配置 10s
        service.run("pg-main", "SELECT 1", null, null);
        verify(executor).query(eq("pg-main"), eq("SELECT 1"), isNull(), eq(200), eq(10));
    }
}

package com.sqlpipeline.datasource.metadata;

import com.sqlpipeline.datasource.registry.DataSourceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DbMetadataServiceTest {

    private DataSourceRegistry registry;
    private DataSource ds;
    private Connection conn;
    private PreparedStatement ps;
    private ResultSet rs;
    private DbMetadataService service;

    @BeforeEach
    void setUp() throws SQLException {
        registry = mock(DataSourceRegistry.class);
        ds = mock(DataSource.class);
        conn = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        rs = mock(ResultSet.class);
        when(registry.get("k1")).thenReturn(ds);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        service = new DbMetadataService(registry);
    }

    @Test
    void groupsColumnsByTableInOrder() throws SQLException {
        when(rs.next()).thenReturn(true, true, true, true, false);
        when(rs.getString(1)).thenReturn("t_b", "t_a", "t_a", "t_a");
        when(rs.getString(2)).thenReturn("id", "id", "name", "amount");

        List<DbMetadataService.TableMeta> tables = service.tables("k1");

        assertThat(tables).hasSize(2);
        assertThat(tables.get(0).name()).isEqualTo("t_b");
        assertThat(tables.get(0).columns()).containsExactly("id");
        assertThat(tables.get(1).name()).isEqualTo("t_a");
        assertThat(tables.get(1).columns()).containsExactly("id", "name", "amount");
    }

    @Test
    void cachesWithinTtl() throws SQLException {
        when(rs.next()).thenReturn(false);

        service.tables("k1");
        service.tables("k1");

        verify(ds, times(1)).getConnection();
    }

    @Test
    void evictForcesReload() throws SQLException {
        when(rs.next()).thenReturn(false);

        service.tables("k1");
        service.evict("k1");
        service.tables("k1");

        verify(ds, times(2)).getConnection();
    }

    @Test
    void evictNullIsNoop() {
        DbMetadataService spied = Mockito.spy(service);
        spied.evict(null);
        // 无异常即通过：null 不应触发 NPE
        assertThat(spied).isNotNull();
    }
}

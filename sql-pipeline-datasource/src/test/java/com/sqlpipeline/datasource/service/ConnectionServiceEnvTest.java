package com.sqlpipeline.datasource.service;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.datasource.config.DatasourceProperties;
import com.sqlpipeline.datasource.crypto.CryptoService;
import com.sqlpipeline.datasource.entity.DbConnection;
import com.sqlpipeline.datasource.mapper.DbConnectionMapper;
import com.sqlpipeline.datasource.registry.DataSourceRegistry;
import com.sqlpipeline.datasource.registry.PoolBudgetValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 连接 env 字段：create 归一化默认 DEV、非法值 DS0006、update 空串保留旧值、envByKeys 批量映射兜底。 */
class ConnectionServiceEnvTest {

    private DbConnectionMapper mapper;
    private CryptoService crypto;
    private DataSourceRegistry registry;
    private PoolBudgetValidator poolBudgetValidator;
    private DatasourceProperties datasourceProperties;
    private ConnectionService service;

    @BeforeEach
    void setUp() {
        mapper = mock(DbConnectionMapper.class);
        crypto = mock(CryptoService.class);
        registry = mock(DataSourceRegistry.class);
        poolBudgetValidator = mock(PoolBudgetValidator.class);
        datasourceProperties = new DatasourceProperties();
        service = new ConnectionService(mapper, crypto, registry,
                Mockito.mock(MessageSource.class), poolBudgetValidator, datasourceProperties);
    }

    private DbConnection conn(String connKey) {
        DbConnection e = new DbConnection();
        e.setConnKey(connKey);
        e.setJdbcUrl("jdbc:postgresql://localhost:5432/db");
        e.setUsername("u");
        return e;
    }

    @Test
    void createDefaultsEnvToDev() {
        DbConnection e = conn("k1");
        when(mapper.selectByKey("k1")).thenReturn(null);
        when(crypto.encrypt(anyString())).thenReturn("enc");

        service.create(e, "pwd", "op");

        assertThat(e.getEnv()).isEqualTo("DEV");
        verify(mapper).insert(e);
    }

    @Test
    void createNormalizesLowercaseAndBlank() {
        DbConnection lower = conn("k1");
        DbConnection blank = conn("k2");
        when(mapper.selectByKey(anyString())).thenReturn(null);
        when(crypto.encrypt(anyString())).thenReturn("enc");

        service.create(lower, "pwd", "op");
        service.create(blank, "pwd", "op");

        assertThat(lower.getEnv()).isEqualTo("DEV");
        assertThat(blank.getEnv()).isEqualTo("DEV");
    }

    @Test
    void createKeepsExplicitProd() {
        DbConnection e = conn("k1");
        e.setEnv("prod"); // 大小写归一化
        when(mapper.selectByKey("k1")).thenReturn(null);
        when(crypto.encrypt(anyString())).thenReturn("enc");

        service.create(e, "pwd", "op");

        assertThat(e.getEnv()).isEqualTo("PROD");
    }

    @Test
    void createRejectsInvalidEnvWithDs0006() {
        DbConnection e = conn("k1");
        e.setEnv("UAT");
        when(mapper.selectByKey("k1")).thenReturn(null);
        when(crypto.encrypt(anyString())).thenReturn("enc");

        assertThatThrownBy(() -> service.create(e, "pwd", "op"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode()).isEqualTo(ErrorCode.DS_ENV_INVALID));
        verify(mapper, never()).insert(any());
    }

    @Test
    void updateBlankEnvKeepsOldValue() {
        DbConnection old = conn("k1");
        old.setId(1L);
        old.setEnv("PROD");
        when(mapper.selectById(1L)).thenReturn(old);

        DbConnection patch = new DbConnection();
        patch.setEnv(""); // 空串 = 保留旧值

        service.update(1L, patch, null, "op");

        assertThat(old.getEnv()).isEqualTo("PROD");
    }

    @Test
    void updateInvalidEnvThrowsDs0006() {
        DbConnection old = conn("k1");
        old.setId(1L);
        old.setEnv("DEV");
        when(mapper.selectById(1L)).thenReturn(old);

        DbConnection patch = new DbConnection();
        patch.setEnv("UAT");

        assertThatThrownBy(() -> service.update(1L, patch, null, "op"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode()).isEqualTo(ErrorCode.DS_ENV_INVALID));
    }

    @Test
    void envByKeysMapsAndDefaultsMissing() {
        DbConnection prod = new DbConnection();
        prod.setConnKey("prod-conn");
        prod.setEnv("PROD");
        DbConnection dev = new DbConnection();
        dev.setConnKey("dev-conn");
        dev.setEnv(null); // 库中空值兜底 DEV
        when(mapper.selectEnvByKeys(List.of("prod-conn", "dev-conn")))
                .thenReturn(List.of(prod, dev));

        Map<String, String> env = service.envByKeys(List.of("prod-conn", "dev-conn", " ", "prod-conn"));

        assertThat(env).containsEntry("prod-conn", "PROD").containsEntry("dev-conn", "DEV");
        // 缺失 key 兜底 DEV（调用方 getOrDefault）
        assertThat(env.get("missing")).isNull();
    }

    @Test
    void envByKeysEmptyInputSkipsQuery() {
        assertThat(service.envByKeys(null)).isEmpty();
        assertThat(service.envByKeys(List.of(" ", ""))).isEmpty();
        verify(mapper, never()).selectEnvByKeys(any());
    }
}

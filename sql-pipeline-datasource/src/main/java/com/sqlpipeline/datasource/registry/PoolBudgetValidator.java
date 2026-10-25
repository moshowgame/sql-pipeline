package com.sqlpipeline.datasource.registry;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.datasource.config.DatasourceProperties;
import com.sqlpipeline.datasource.entity.DbConnection;
import com.sqlpipeline.datasource.mapper.DbConnectionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 连接池容量全库预算校验（P0）：按规范化 jdbc_url 归组，同一目标库上所有启用连接的
 * pool_size 之和不得超过 pool-budget-per-url，防止多连接定义叠加打爆目标库 max_connections。
 * 超限抛 DS0004；超过 5 个池共享一个目标库时应改用 PgBouncer 侧收敛。
 */
@Component
@RequiredArgsConstructor
public class PoolBudgetValidator {

    private final DbConnectionMapper connectionMapper;
    private final DatasourceProperties properties;

    /**
     * 校验「候选 url + 候选 pool_size」加入后是否超预算。
     *
     * @param jdbcUrl   本次要保存/更新的 jdbc_url
     * @param poolSize  本次要保存/更新的 pool_size
     * @param excludeId 更新场景排除自身 id（新建传 null）
     */
    public void validate(String jdbcUrl, Integer poolSize, Long excludeId) {
        int budget = properties.getPoolBudgetPerUrl();
        if (budget <= 0 || jdbcUrl == null || poolSize == null || poolSize <= 0) {
            return;
        }
        String normalized = normalize(jdbcUrl);
        List<DbConnection> all = connectionMapper.selectEnabled();
        int used = 0;
        if (all != null) {
            for (DbConnection c : all) {
                if (excludeId != null && excludeId.equals(c.getId())) {
                    continue; // 更新时排除自身旧值
                }
                if (normalized.equals(normalize(c.getJdbcUrl())) && c.getPoolSize() != null) {
                    used += c.getPoolSize();
                }
            }
        }
        int total = used + poolSize;
        if (total > budget) {
            throw BizException.i18n(ErrorCode.DS_POOL_BUDGET, "error.ds.poolBudget",
                    normalized, used, poolSize, budget);
        }
    }

    /** 仅用于日志告警的统计：同一 url 上已注册池的总容量（不含候选）。 */
    public int usedOf(String jdbcUrl) {
        String normalized = normalize(jdbcUrl);
        int used = 0;
        for (DbConnection c : connectionMapper.selectEnabled()) {
            if (normalized.equals(normalize(c.getJdbcUrl())) && c.getPoolSize() != null) {
                used += c.getPoolSize();
            }
        }
        return used;
    }

    /** 归一化：去空白、小写、去掉 ? 及之后的驱动参数（不影响连接目标）。 */
    static String normalize(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim().toLowerCase();
        int q = u.indexOf('?');
        return q >= 0 ? u.substring(0, q) : u;
    }
}

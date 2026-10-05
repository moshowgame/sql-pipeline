package com.sqlpipeline.web.dto;

import com.sqlpipeline.health.entity.SqlDefinition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 健康检查定义创建/更新请求。
 * 断言 = assertType（VALUE 返回值 / ROWS 返回行数）+ assertOp（== != > >= < <=）+ assertValue（期望值）。
 * notifyChannelId：绑定的告警通道（HC_FAIL 只推送到该通道；空=不告警）。
 */
public record HealthCheckSaveReq(
        @NotBlank String name,
        @NotBlank String connKey,
        @NotBlank String sqlText,
        @Size(max = 4000) String paramsJson,
        String assertType,
        String assertOp,
        BigDecimal assertValue,
        Long notifyChannelId,
        String cronExpr,
        Integer timeoutSec,
        Integer enabled) {

    public SqlDefinition toEntity() {
        SqlDefinition def = new SqlDefinition();
        def.setName(name);
        def.setConnKey(connKey);
        def.setSqlText(sqlText);
        def.setParamsJson(paramsJson);
        def.setAssertType(assertType);
        def.setAssertOp(assertOp);
        def.setAssertValue(assertValue);
        def.setNotifyChannelId(notifyChannelId);
        def.setCronExpr(cronExpr);
        def.setTimeoutSec(timeoutSec);
        def.setEnabled(enabled);
        return def;
    }
}

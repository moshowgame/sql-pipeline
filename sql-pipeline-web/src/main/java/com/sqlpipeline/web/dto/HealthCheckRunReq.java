package com.sqlpipeline.web.dto;

import java.util.Map;

/**
 * 手动执行健康检查请求：
 * - params：命名参数覆盖（与定义的默认参数合并，同名覆盖）
 * - headRows：结果预览行数（可选，1~500，默认 100；定时执行不受此参数影响）
 */
public record HealthCheckRunReq(Map<String, Object> params, Integer headRows) {
}

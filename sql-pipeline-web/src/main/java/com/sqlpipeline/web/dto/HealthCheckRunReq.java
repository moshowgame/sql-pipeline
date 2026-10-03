package com.sqlpipeline.web.dto;

import java.util.Map;

/** 手动执行健康检查请求：params 为命名参数覆盖（与定义的默认参数合并，同名覆盖）。 */
public record HealthCheckRunReq(Map<String, Object> params) {
}

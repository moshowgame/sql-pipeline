package com.sqlpipeline.web.dto;

import java.util.List;

/** 手动执行健康检查请求：params 可覆盖定义中的默认参数（按 ? 顺序绑定）。 */
public record HealthCheckRunReq(List<Object> params) {
}

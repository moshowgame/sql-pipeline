package com.sqlpipeline.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 即席查询请求（编辑器试运行 / SQL Console）：
 * - sql：一段或多段 SELECT（分号分隔，后端拆分逐条执行，上限 20 条）
 * - maxRows：单语句返回行数（1~1000，默认 200）
 * - timeoutSec：单语句超时（1~120，默认取连接配置）
 */
public record AdHocQueryReq(@NotBlank String sql, Integer maxRows, Integer timeoutSec) {
}

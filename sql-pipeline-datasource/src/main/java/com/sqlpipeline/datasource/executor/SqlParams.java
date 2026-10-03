package com.sqlpipeline.datasource.executor;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 健康检查 SQL 命名参数解析：
 * <ul>
 *   <li>SQL 中以 {@code ${name}} 占位（如 {@code WHERE created_at >= ${date}}）</li>
 *   <li>参数值为 JSON 对象，如 {@code {"date":"2026-10-01","status":1}}</li>
 *   <li>解析时将 {@code ${name}} 依出现顺序替换为 {@code ?}，并生成对应参数列表供 PreparedStatement 绑定</li>
 * </ul>
 * 注意：纯文本替换不感知字符串字面量，SQL 中的 ${} 一律视为占位符。
 */
public final class SqlParams {

    private static final Pattern NAMED = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    private SqlParams() {
    }

    /** 解析结果：占位符替换后的 SQL 与按序参数列表。 */
    public record Resolved(String sql, List<Object> params) {
    }

    public static boolean hasNamedPlaceholders(String sql) {
        return sql != null && NAMED.matcher(sql).find();
    }

    /**
     * 解析命名占位符。SQL 无占位符时原样返回（参数列表为空）；
     * 有占位符但 values 中缺少对应 key 时抛 HC_CONFIG_INVALID。
     */
    public static Resolved resolve(String sql, Map<String, Object> values) {
        if (!hasNamedPlaceholders(sql)) {
            return new Resolved(sql, List.of());
        }
        List<Object> params = new ArrayList<>();
        Matcher m = NAMED.matcher(sql);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = m.group(1);
            if (values == null || !values.containsKey(name)) {
                throw new BizException(ErrorCode.HC_CONFIG_INVALID,
                        "SQL 占位符 ${" + name + "} 在参数中缺少对应值");
            }
            params.add(values.get(name));
            m.appendReplacement(sb, "?");
        }
        m.appendTail(sb);
        return new Resolved(sb.toString(), params);
    }
}

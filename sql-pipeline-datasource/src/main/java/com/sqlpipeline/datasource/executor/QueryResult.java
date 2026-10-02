package com.sqlpipeline.datasource.executor;

import com.sqlpipeline.common.util.JsonUtils;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只读查询结果：列名、行数据（按列名取值）、行数与截断标记。 */
@Data
public class QueryResult {

    private List<String> columns = new ArrayList<>();
    private List<Map<String, Object>> rows = new ArrayList<>();
    private int rowCount;
    private boolean truncated;

    /** 取第 row 行第 col 列（均从 0 开始），越界返回 null。 */
    public Object cell(int row, int col) {
        if (row < 0 || row >= rows.size() || col < 0 || col >= columns.size()) {
            return null;
        }
        return rows.get(row).get(columns.get(col));
    }

    /** 前 maxRows 行的 JSON 摘要（用于执行记录 result_head）。 */
    public String toJson(int maxRows) {
        List<Map<String, Object>> head = rows.size() > maxRows ? rows.subList(0, maxRows) : rows;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("columns", columns);
        body.put("rowCount", rowCount);
        body.put("truncated", truncated);
        body.put("rows", head);
        return JsonUtils.toJson(body);
    }
}

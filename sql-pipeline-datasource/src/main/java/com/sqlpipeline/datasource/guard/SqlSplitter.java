package com.sqlpipeline.datasource.guard;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 拆分器：按分号拆分语句，供 SqlGuard 逐条校验、发布执行器逐条执行。
 * <p>
 * 基于状态机实现，正确跳过以下内容中的分号与注释（面向 PostgreSQL）：
 * <ul>
 *   <li>单引号字符串（含 {@code ''} 与 {@code \} 转义）</li>
 *   <li>双引号标识符</li>
 *   <li>Dollar-quote 字符串（{@code $$...$$} / {@code $tag$...$tag$}，函数体常用）</li>
 *   <li>{@code --} 行注释与 {@code /* ... *}{@code /} 块注释（PostgreSQL 支持嵌套）</li>
 * </ul>
 * 输出的语句已剥离注释。
 */
public final class SqlSplitter {

    private SqlSplitter() {
    }

    public static List<String> split(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        int n = raw.length();
        int i = 0;
        while (i < n) {
            char c = raw.charAt(i);
            if (c == '-' && i + 1 < n && raw.charAt(i + 1) == '-') {
                i = skipLineComment(raw, i);
                cur.append(' ');
                continue;
            }
            if (c == '/' && i + 1 < n && raw.charAt(i + 1) == '*') {
                i = skipBlockComment(raw, i);
                cur.append(' ');
                continue;
            }
            if (c == '\'') {
                i = copyQuoted(raw, i, '\'', true, cur);
                continue;
            }
            if (c == '"') {
                i = copyQuoted(raw, i, '"', false, cur);
                continue;
            }
            if (c == '$') {
                int tagEnd = dollarTagEnd(raw, i);
                if (tagEnd > 0) {
                    i = copyDollarQuoted(raw, i, tagEnd, cur);
                    continue;
                }
            }
            if (c == ';') {
                flush(cur, out);
                i++;
                continue;
            }
            cur.append(c);
            i++;
        }
        flush(cur, out);
        return out;
    }

    private static int skipLineComment(String s, int start) {
        int i = start + 2;
        while (i < s.length() && s.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    private static int skipBlockComment(String s, int start) {
        int depth = 0;
        int i = start;
        while (i < s.length()) {
            if (i + 1 < s.length() && s.charAt(i) == '/' && s.charAt(i + 1) == '*') {
                depth++;
                i += 2;
                continue;
            }
            if (i + 1 < s.length() && s.charAt(i) == '*' && s.charAt(i + 1) == '/') {
                depth--;
                i += 2;
                if (depth == 0) {
                    return i;
                }
                continue;
            }
            i++;
        }
        return s.length();
    }

    private static int copyQuoted(String s, int start, char quote, boolean allowBackslash, StringBuilder out) {
        out.append(quote);
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            out.append(c);
            if (allowBackslash && c == '\\' && i + 1 < s.length()) {
                out.append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            i++;
            if (c == quote) {
                if (i < s.length() && s.charAt(i) == quote) {
                    out.append(quote);
                    i++;
                    continue;
                }
                return i;
            }
        }
        return i;
    }

    /** 返回 dollar-tag 的结束下标（即结尾 {@code $} 的位置），非 dollar-quote 起始时返回 -1。 */
    private static int dollarTagEnd(String s, int start) {
        int j = start + 1;
        while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
            j++;
        }
        if (j < s.length() && s.charAt(j) == '$') {
            return j;
        }
        return -1;
    }

    private static int copyDollarQuoted(String s, int start, int tagEnd, StringBuilder out) {
        String tag = s.substring(start, tagEnd + 1);
        out.append(tag);
        int close = s.indexOf(tag, tagEnd + 1);
        if (close < 0) {
            out.append(s, tagEnd + 1, s.length());
            return s.length();
        }
        out.append(s, tagEnd + 1, close + tag.length());
        return close + tag.length();
    }

    private static void flush(StringBuilder cur, List<String> out) {
        String s = cur.toString().trim();
        if (!s.isEmpty()) {
            out.add(s);
        }
        cur.setLength(0);
    }
}

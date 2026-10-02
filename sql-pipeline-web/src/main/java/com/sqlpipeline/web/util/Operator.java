package com.sqlpipeline.web.util;

import static org.springframework.util.StringUtils.hasText;

/** 操作者身份：一期无登录态，从 X-Operator 请求头取，缺省 anonymous。 */
public final class Operator {

    public static final String HEADER = "X-Operator";

    private Operator() {
    }

    public static String of(String header) {
        return hasText(header) ? header.trim() : "anonymous";
    }
}

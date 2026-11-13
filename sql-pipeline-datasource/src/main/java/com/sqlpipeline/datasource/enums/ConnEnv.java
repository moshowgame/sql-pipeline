package com.sqlpipeline.datasource.enums;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;

/**
 * 连接环境标识（仅 DEV / PROD 两档，默认 DEV）。
 * PROD 连接在前端红色警示，Release Plan 通过 default_conn_key 运行时动态读取（不落库快照）。
 */
public final class ConnEnv {

    public static final String DEV = "DEV";
    public static final String PROD = "PROD";

    private ConnEnv() {
    }

    /** 归一化：null/空白 → DEV；非法值抛 DS0006。 */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEV;
        }
        String v = raw.trim().toUpperCase();
        if (!DEV.equals(v) && !PROD.equals(v)) {
            throw BizException.i18n(ErrorCode.DS_ENV_INVALID, "error.ds.envInvalid", raw);
        }
        return v;
    }
}

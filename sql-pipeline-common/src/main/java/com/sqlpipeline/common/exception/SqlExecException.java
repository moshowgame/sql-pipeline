package com.sqlpipeline.common.exception;

import com.sqlpipeline.common.error.ErrorCode;

/** 动态 SQL 执行失败（超时 / 执行错误）时抛出。 */
public class SqlExecException extends BizException {

    public SqlExecException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}

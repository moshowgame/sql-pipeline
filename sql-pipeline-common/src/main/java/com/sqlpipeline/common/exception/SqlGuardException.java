package com.sqlpipeline.common.exception;

import com.sqlpipeline.common.error.ErrorCode;

/** SqlGuard 只读校验不通过时抛出。 */
public class SqlGuardException extends BizException {

    public SqlGuardException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}

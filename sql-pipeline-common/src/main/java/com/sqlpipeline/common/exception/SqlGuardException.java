package com.sqlpipeline.common.exception;

import com.sqlpipeline.common.error.ErrorCode;

/** SqlGuard 只读校验不通过时抛出。 */
public class SqlGuardException extends BizException {

    public SqlGuardException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public SqlGuardException(ErrorCode errorCode, String messageKey, Object[] args) {
        super(errorCode, messageKey, args);
    }

    public static SqlGuardException i18n(ErrorCode errorCode, String messageKey, Object... args) {
        return new SqlGuardException(errorCode, messageKey, args);
    }
}

package com.sqlpipeline.common.exception;

import com.sqlpipeline.common.error.ErrorCode;
import lombok.Getter;

@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    /** i18n 消息键；非空时由异常处理器按当前 locale 经 MessageSource 渲染。 */
    private final String messageKey;

    /** 消息占位参数（MessageFormat {0} {1} …）。 */
    private final Object[] args;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage());
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.messageKey = null;
        this.args = null;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.messageKey = null;
        this.args = null;
    }

    /** i18n：keyed 构造（供子类与静态工厂使用）。 */
    protected BizException(ErrorCode errorCode, String messageKey, Object[] args) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
        this.messageKey = messageKey;
        this.args = args;
    }

    /** i18n：keyed 构造（带 cause）。 */
    protected BizException(ErrorCode errorCode, String messageKey, Object[] args, Throwable cause) {
        super(errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
        this.messageKey = messageKey;
        this.args = args;
    }

    /** 构造带 i18n 消息键的异常：渲染在 GlobalExceptionHandler 按当前 locale 完成。 */
    public static BizException i18n(ErrorCode errorCode, String messageKey, Object... args) {
        return new BizException(errorCode, messageKey, args);
    }
}

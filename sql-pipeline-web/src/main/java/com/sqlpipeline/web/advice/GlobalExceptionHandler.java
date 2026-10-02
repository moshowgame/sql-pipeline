package com.sqlpipeline.web.advice;

import com.sqlpipeline.common.api.R;
import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.common.exception.SqlGuardException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SqlGuardException.class)
    public ResponseEntity<R<Void>> handleGuard(SqlGuardException e) {
        // 拦截事件业务层已有 WARN 日志，这里按 4xx 返回
        return ResponseEntity.status(e.getErrorCode().getHttpStatus())
                .body(R.fail(e.getErrorCode().getCode(), e.getMessage()));
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBiz(BizException e) {
        return ResponseEntity.status(e.getErrorCode().getHttpStatus())
                .body(R.fail(e.getErrorCode().getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        return status(ErrorCode.SYS_PARAM_INVALID, msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<R<Void>> handleConstraint(ConstraintViolationException e) {
        return status(ErrorCode.SYS_PARAM_INVALID, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        return status(ErrorCode.SYS_PARAM_INVALID, "请求体解析失败: " + e.getMostSpecificCause().getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<R<Void>> handleConflict(DataIntegrityViolationException e) {
        return status(ErrorCode.SYS_PARAM_INVALID, "数据写入冲突（唯一约束）: " + e.getMostSpecificCause().getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnknown(Exception e) {
        log.error("unhandled exception, traceId={}", org.slf4j.MDC.get("traceId"), e);
        return status(ErrorCode.SYS_INTERNAL, ErrorCode.SYS_INTERNAL.getDefaultMessage());
    }

    private ResponseEntity<R<Void>> status(ErrorCode code, String message) {
        return ResponseEntity.status(code.getHttpStatus()).body(R.fail(code.getCode(), message));
    }
}

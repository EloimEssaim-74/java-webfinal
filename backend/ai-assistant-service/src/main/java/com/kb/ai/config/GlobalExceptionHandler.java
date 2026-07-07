package com.kb.ai.config;

import com.kb.common.exception.BusinessException;
import com.kb.common.result.Result;
import com.kb.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * AI 服务全局异常处理器.
 *
 * <p>统一返回 {@code {code, message}} 格式，与平台其他服务保持一致.
 * 之前缺少此处理器，校验失败时返回 SpringBoot 默认的 {@code {timestamp, status, error}} 格式.</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        log.warn("AI service business exception: code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity
                .status(e.getCode())
                .body(Result.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.bind.support.WebExchangeBindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidationException(org.springframework.web.bind.support.WebExchangeBindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        return Result.error(ResultCode.BAD_REQUEST, message);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("AI service unexpected error", e);
        return Result.error(ResultCode.INTERNAL_ERROR);
    }
}

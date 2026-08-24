package com.fundradar.core.common.web;

import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.integration.ai.AiServiceUnavailableException;
import com.fundradar.core.integration.ai.FundNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(FundNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleFundNotFound(FundNotFoundException exception) {
        LOGGER.warn("GlobalExceptionHandler.handleFundNotFound   >>> {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.failure("FUND_NOT_FOUND", "未找到该基金。"));
    }

    @ExceptionHandler(AiServiceUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleAiServiceUnavailable(AiServiceUnavailableException exception) {
        LOGGER.error("GlobalExceptionHandler.handleAiServiceUnavailable   >>> AI service is unavailable", exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.failure("AI_SERVICE_UNAVAILABLE", "分析服务暂时不可用，请稍后重试。"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        LOGGER.error("GlobalExceptionHandler.handleUnexpectedException   >>> unexpected request failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure("INTERNAL_ERROR", "系统暂时不可用，请稍后重试。"));
    }
}

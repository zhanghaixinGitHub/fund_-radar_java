package com.fundradar.core.common.web;

import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.integration.ai.AiServiceUnavailableException;
import com.fundradar.core.integration.ai.FocusedNavSyncBaselineMissingException;
import com.fundradar.core.integration.ai.FocusedNavSyncFailedException;
import com.fundradar.core.integration.ai.FocusedNavSyncInProgressException;
import com.fundradar.core.integration.ai.FundNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Java 对外接口的统一异常转换器。
 *
 * 将参数校验、基金不存在、AI 内部服务不可用和未预期异常转换为稳定的 HTTP 状态码及 ApiResponse 结构。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 将请求体校验失败转换为 400，返回第一条字段校验提示。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(
            MethodArgumentNotValidException exception
    ) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("请求参数不合法。");
        LOGGER.warn("GlobalExceptionHandler.handleValidationException   >>> {}", message);
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("VALIDATION_ERROR", message));
    }

    /** 将内部读模型返回的基金不存在异常转换为 404。 */
    @ExceptionHandler(FundNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleFundNotFound(FundNotFoundException exception) {
        LOGGER.warn("GlobalExceptionHandler.handleFundNotFound   >>> {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.failure("FUND_NOT_FOUND", "未找到该基金。"));
    }

    /** 将日期范围等参数错误转换为 400，而不是泄露为内部错误。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException exception) {
        LOGGER.warn("GlobalExceptionHandler.handleIllegalArgument   >>> {}", exception.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("VALIDATION_ERROR", exception.getMessage()));
    }

    /** 将 Python AI 内部读模型不可用转换为 503，不泄露底层连接或令牌信息。 */
    @ExceptionHandler(AiServiceUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleAiServiceUnavailable(AiServiceUnavailableException exception) {
        LOGGER.error("GlobalExceptionHandler.handleAiServiceUnavailable   >>> AI service is unavailable", exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.failure("AI_SERVICE_UNAVAILABLE", "分析服务暂时不可用，请稍后重试。"));
    }

    /** 将重复的人工同步请求转换为 409，避免用户重复触发外部调用。 */
    @ExceptionHandler(FocusedNavSyncInProgressException.class)
    public ResponseEntity<ApiResponse<Void>> handleFocusedNavSyncInProgress(FocusedNavSyncInProgressException exception) {
        LOGGER.warn("GlobalExceptionHandler.handleFocusedNavSyncInProgress   >>> {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failure("FOCUSED_SYNC_IN_PROGRESS", "已有重点基金同步正在执行，请稍后重试。"));
    }

    /** 将历史基线缺失转换为可操作的 422，不误报为同步中的冲突。 */
    @ExceptionHandler(FocusedNavSyncBaselineMissingException.class)
    public ResponseEntity<ApiResponse<Void>> handleFocusedNavSyncBaselineMissing(
            FocusedNavSyncBaselineMissingException exception
    ) {
        LOGGER.warn("GlobalExceptionHandler.handleFocusedNavSyncBaselineMissing   >>> {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(ApiResponse.failure("FOCUSED_SYNC_BASELINE_MISSING", "请先完成重点基金历史净值回填。"));
    }

    /** 将已确认的 Tushare 同步失败转换为 502，不暴露外部响应或凭据。 */
    @ExceptionHandler(FocusedNavSyncFailedException.class)
    public ResponseEntity<ApiResponse<Void>> handleFocusedNavSyncFailed(FocusedNavSyncFailedException exception) {
        LOGGER.error("GlobalExceptionHandler.handleFocusedNavSyncFailed   >>> focused NAV sync failed", exception);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.failure("FOCUSED_SYNC_FAILED", "净值同步未完成，请稍后重试。"));
    }

    /** 记录完整堆栈并将未分类异常转换为通用 500 响应。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        LOGGER.error("GlobalExceptionHandler.handleUnexpectedException   >>> unexpected request failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure("INTERNAL_ERROR", "系统暂时不可用，请稍后重试。"));
    }
}

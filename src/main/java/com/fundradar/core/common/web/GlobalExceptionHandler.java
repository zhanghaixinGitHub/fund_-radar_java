package com.fundradar.core.common.web;

import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.auth.service.AccessDeniedException;
import com.fundradar.core.auth.service.AccountAlreadyExistsException;
import com.fundradar.core.auth.service.AuthenticationRequiredException;
import com.fundradar.core.auth.service.InvalidCredentialsException;
import com.fundradar.core.integration.ai.AiServiceUnavailableException;
import com.fundradar.core.integration.ai.MarketNavSyncInProgressException;
import com.fundradar.core.integration.ai.FundNotFoundException;
import com.fundradar.core.integration.ai.SyncJobNotFoundException;
import com.fundradar.core.watchlist.credit.WatchlistQuotaExceededException;
import com.fundradar.core.notification.service.NotificationNotFoundException;
import com.fundradar.core.watchlist.service.WatchlistRequiredException;
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

    /** 将登录失败统一为 401，避免泄露登录名、状态或密码是否匹配。 */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidCredentials(InvalidCredentialsException exception) {
        LOGGER.warn("GlobalExceptionHandler.handleInvalidCredentials   >>> login rejected");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.failure("INVALID_CREDENTIALS", "手机号或密码错误。"));
    }

    /** 显式注册的重复手机号返回 409，前端可引导用户切换至登录而不写入密码或手机号日志。 */
    @ExceptionHandler(AccountAlreadyExistsException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccountAlreadyExists(AccountAlreadyExistsException exception) {
        LOGGER.warn("GlobalExceptionHandler.handleAccountAlreadyExists   >>> registration rejected due to existing account");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failure("ACCOUNT_ALREADY_EXISTS", "该手机号已注册，请直接登录。"));
    }

    /** 将缺失、失效或已撤销会话统一转换为 401。 */
    @ExceptionHandler(AuthenticationRequiredException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationRequired(AuthenticationRequiredException exception) {
        LOGGER.warn("GlobalExceptionHandler.handleAuthenticationRequired   >>> authentication required");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.failure("AUTHENTICATION_REQUIRED", "登录已失效，请重新登录。"));
    }

    /** 管理接口由服务端角色校验，普通用户访问时只返回稳定的 403。 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException exception) {
        LOGGER.warn("GlobalExceptionHandler.handleAccessDenied   >>> access denied");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.failure("ACCESS_DENIED", "你没有执行该操作的权限。"));
    }

    /** 将 Python AI 内部读模型不可用转换为 503，不泄露底层连接或令牌信息。 */
    @ExceptionHandler(AiServiceUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleAiServiceUnavailable(AiServiceUnavailableException exception) {
        LOGGER.error("GlobalExceptionHandler.handleAiServiceUnavailable   >>> AI service is unavailable", exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.failure("AI_SERVICE_UNAVAILABLE", "分析服务暂时不可用，请稍后重试。"));
    }

    /** 将重复的人工同步请求转换为 409，避免用户重复触发外部调用。 */
    @ExceptionHandler(MarketNavSyncInProgressException.class)
    public ResponseEntity<ApiResponse<Void>> handleMarketNavSyncInProgress(MarketNavSyncInProgressException exception) {
        LOGGER.warn("GlobalExceptionHandler.handleMarketNavSyncInProgress   >>> {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failure("MARKET_SYNC_IN_PROGRESS", "已有基金市场同步正在执行，请稍后重试。"));
    }

    /** 当前用户超出免费额度及已发放试用积分支持的有效关注上限时返回稳定 409。 */
    @ExceptionHandler(WatchlistQuotaExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleWatchlistQuotaExceeded(WatchlistQuotaExceededException exception) {
        LOGGER.warn("GlobalExceptionHandler.handleWatchlistQuotaExceeded   >>> watchlist quota exceeded");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failure("WATCHLIST_QUOTA_EXCEEDED", exception.getMessage()));
    }

    /** 基金存在但未被当前用户关注时，完整详情必须拒绝而不能按公开详情降级。 */
    @ExceptionHandler(WatchlistRequiredException.class)
    public ResponseEntity<ApiResponse<Void>> handleWatchlistRequired(WatchlistRequiredException exception) {
        LOGGER.warn("GlobalExceptionHandler.handleWatchlistRequired   >>> full detail rejected because fund is not followed");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.failure("WATCHLIST_REQUIRED", "请先将该基金加入关注列表后再查看完整详情。"));
    }

    /** 当前用户范围内不存在通知时返回 404，避免泄露其他用户的提醒记录。 */
    @ExceptionHandler(NotificationNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotificationNotFound(NotificationNotFoundException exception) {
        LOGGER.warn("GlobalExceptionHandler.handleNotificationNotFound   >>> notification not found for current user");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.failure("NOTIFICATION_NOT_FOUND", "未找到该通知。"));
    }

    /** Python 服务重启后内存任务不存在时，提示用户重新发起而非伪造成同步失败。 */
    @ExceptionHandler(SyncJobNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleSyncJobNotFound(SyncJobNotFoundException exception) {
        LOGGER.warn("GlobalExceptionHandler.handleSyncJobNotFound   >>> {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.failure("SYNC_JOB_NOT_FOUND", "同步任务状态已失效，请重新发起同步。"));
    }

    /** 记录完整堆栈并将未分类异常转换为通用 500 响应。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        LOGGER.error("GlobalExceptionHandler.handleUnexpectedException   >>> unexpected request failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure("INTERNAL_ERROR", "系统暂时不可用，请稍后重试。"));
    }
}

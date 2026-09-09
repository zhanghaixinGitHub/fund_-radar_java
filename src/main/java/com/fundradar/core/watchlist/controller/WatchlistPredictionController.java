package com.fundradar.core.watchlist.controller;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.integration.ai.AiPredictionClient;
import com.fundradar.core.watchlist.api.WatchlistPredictionResponse;
import com.fundradar.core.watchlist.service.WatchlistRequiredException;
import com.fundradar.core.watchlist.service.WatchlistService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 预测独立读取，所有条件均在服务端校验；不能用userId或前端isWatched绕过。 */
@RestController
@Validated
@RequestMapping("/api/v1/watchlist")
public class WatchlistPredictionController {
    private final WatchlistService watchlist;
    private final AiPredictionClient prediction;

    public WatchlistPredictionController(WatchlistService watchlist, AiPredictionClient prediction) {
        this.watchlist = watchlist;
        this.prediction = prediction;
    }

    /** 未登录、权限不足、非本人关注，在调用Python前即拒绝。成功响应不允许共享缓存。 */
    @GetMapping("/{fundCode}/prediction")
    public ApiResponse<WatchlistPredictionResponse> read(
            @PathVariable @Pattern(regexp = "^\\d{6}$", message = "基金代码必须为6位数字。") String fundCode,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, private");
        CurrentUserContext.requirePermission(PermissionCode.FUND_READ);
        CurrentUserContext.requirePermission(PermissionCode.WATCHLIST_SELF_READ);
        if (!watchlist.findCurrentUserFollowedFundCodes(List.of(fundCode)).contains(fundCode)) {
            throw new WatchlistRequiredException();
        }
        return ApiResponse.success(prediction.read(fundCode));
    }

    /** 本控制器的参数校验错误明确返回400；不泄露方法名或内部异常堆栈。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> invalidFundCode(ConstraintViolationException ignored) {
        return ResponseEntity.badRequest().body(ApiResponse.failure("INVALID_ARGUMENT", "基金代码必须为6位数字。"));
    }
}

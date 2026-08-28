package com.fundradar.core.watchlist.controller;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.fund.api.WatchlistFundDetailResponse;
import com.fundradar.core.fund.service.FundQueryService;
import com.fundradar.core.watchlist.api.CreateWatchlistItemRequest;
import com.fundradar.core.watchlist.api.WatchlistItemResponse;
import com.fundradar.core.watchlist.api.WatchlistPageResponse;
import com.fundradar.core.watchlist.service.WatchlistService;
import com.fundradar.core.watchlist.service.WatchlistRequiredException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录用户的关注列表接口；数据范围始终取服务端认证上下文。
 *
 * <p>关联文档：docs_zhx/requirements/user-auth-and-access.md；
 * docs_zhx/design/user-auth-and-access.md；docs_zhx/testcase/user-auth-and-access.md；
 * docs_zhx/requirements/fund-detail-expansion.md；
 * docs_zhx/design/fund-detail-expansion.md；docs_zhx/testcase/fund-detail-expansion.md。</p>
 */
@RestController
@Validated
@RequestMapping("/api/v1/watchlist")
public class WatchlistController {

    private final WatchlistService watchlistService;
    private final FundQueryService fundQueryService;

    public WatchlistController(WatchlistService watchlistService, FundQueryService fundQueryService) {
        this.watchlistService = watchlistService;
        this.fundQueryService = fundQueryService;
    }

    /** 查询当前登录用户的关注基金分页；默认每页 10 条，类型筛选由服务端执行。 */
    @GetMapping
    public ApiResponse<WatchlistPageResponse> listWatchlist(
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            @jakarta.validation.constraints.Pattern(
                    regexp = "^(BOND|STOCK|MIXED|INDEX|MONEY|QDII|FOF|OTHER)$",
                    message = "基金类型筛选参数无效。"
            ) String fundType,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1")
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(10_000) int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "10")
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(50) int pageSize
    ) {
        CurrentUserContext.requirePermission(PermissionCode.WATCHLIST_SELF_READ);
        return ApiResponse.success(watchlistService.listCurrentUserItems(fundType, page, pageSize));
    }

    /**
     * 查询当前用户已关注基金的完整详情。先验证当前会话的关注关系，再请求 Python
     * 只读资料；请求中不传递用户标识或关注状态。
     * 关联文档：docs_zhx/requirements/fund-detail-expansion.md、
     * docs_zhx/design/fund-detail-expansion.md、docs_zhx/testcase/fund-detail-expansion.md。
     */
    @GetMapping("/{fundCode}/detail")
    public ApiResponse<WatchlistFundDetailResponse> getCurrentUserWatchlistFundDetail(
            @PathVariable @Pattern(regexp = "^\\d{6}$", message = "基金代码必须为 6 位数字。") String fundCode
    ) {
        CurrentUserContext.requirePermission(PermissionCode.FUND_READ);
        CurrentUserContext.requirePermission(PermissionCode.WATCHLIST_SELF_READ);
        boolean followed = watchlistService.findCurrentUserFollowedFundCodes(java.util.List.of(fundCode))
                .contains(fundCode);
        if (!followed) {
            throw new WatchlistRequiredException();
        }
        return ApiResponse.success(fundQueryService.getWatchlistFundDetail(fundCode).withWatchStatus());
    }

    /** 将基金加入当前本地用户的关注列表；重复添加为幂等操作。 */
    @PostMapping
    public ApiResponse<WatchlistItemResponse> addWatchlistItem(
            @Valid @RequestBody CreateWatchlistItemRequest request
    ) {
        CurrentUserContext.requirePermission(PermissionCode.WATCHLIST_SELF_WRITE);
        return ApiResponse.success(watchlistService.addCurrentUserItem(request.fundCode()));
    }

    /** 从当前本地用户的关注列表移除基金；不存在时同样安全返回成功。 */
    @DeleteMapping("/{fundCode}")
    public ApiResponse<Void> removeWatchlistItem(
            @PathVariable @Pattern(regexp = "^\\d{6}$", message = "基金代码必须为 6 位数字。") String fundCode
    ) {
        CurrentUserContext.requirePermission(PermissionCode.WATCHLIST_SELF_WRITE);
        watchlistService.removeCurrentUserItem(fundCode);
        return ApiResponse.success(null);
    }
}

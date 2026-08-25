package com.fundradar.core.watchlist.controller;

import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.watchlist.api.CreateWatchlistItemRequest;
import com.fundradar.core.watchlist.api.WatchlistItemResponse;
import com.fundradar.core.watchlist.service.WatchlistService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * M1 单用户部署模式下的当前用户关注列表接口。
 *
 * 当前实现使用固定的本地用户标识；接入正式认证后应由认证上下文替代，接口语义保持不变。
 *
 * <p>关联文档：docs_zhx/requirements/fund-radar.md；
 * docs_zhx/design/fund-radar.md；docs_zhx/testcase/fund-radar.md。</p>
 */
@RestController
@RequestMapping("/api/v1/watchlist")
public class WatchlistController {

    private final WatchlistService watchlistService;

    public WatchlistController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    /** 查询当前本地用户已关注的全部基金。 */
    @GetMapping
    public ApiResponse<List<WatchlistItemResponse>> listWatchlist() {
        return ApiResponse.success(watchlistService.listCurrentUserItems());
    }

    /** 将基金加入当前本地用户的关注列表；重复添加为幂等操作。 */
    @PostMapping
    public ApiResponse<WatchlistItemResponse> addWatchlistItem(
            @Valid @RequestBody CreateWatchlistItemRequest request
    ) {
        return ApiResponse.success(watchlistService.addCurrentUserItem(request.fundCode()));
    }

    /** 从当前本地用户的关注列表移除基金；不存在时同样安全返回成功。 */
    @DeleteMapping("/{fundCode}")
    public ApiResponse<Void> removeWatchlistItem(
            @PathVariable @Pattern(regexp = "^\\d{6}$", message = "基金代码必须为 6 位数字。") String fundCode
    ) {
        watchlistService.removeCurrentUserItem(fundCode);
        return ApiResponse.success(null);
    }
}

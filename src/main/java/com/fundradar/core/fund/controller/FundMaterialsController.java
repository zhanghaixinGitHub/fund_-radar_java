package com.fundradar.core.fund.controller;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.fund.api.*;
import com.fundradar.core.fund.service.FundMaterialsQueryService;
import com.fundradar.core.fund.service.FundQueryService;
import com.fundradar.core.watchlist.service.WatchlistService;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 市场与关注详情共用基金公共资料；个人关注接口继续保留原来的本人关系校验。 */
@Validated
@RestController
@RequestMapping("/api/v1/funds")
public class FundMaterialsController {
    private final FundMaterialsQueryService materials;
    private final FundQueryService funds;
    private final WatchlistService watchlist;
    public FundMaterialsController(FundMaterialsQueryService materials, FundQueryService funds,
            WatchlistService watchlist) {
        this.materials = materials; this.funds = funds; this.watchlist = watchlist;
    }

    /** 方法参数由验证代理检查；边界错误应返回可理解的 400，而非被兜底为服务故障。 */
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> invalidQuery() {
        return ResponseEntity.badRequest().body(ApiResponse.failure("VALIDATION_ERROR",
                "查询条件不正确，请检查基金代码、公司代码和分页范围。"));
    }

    /** 完整资料现在对具有基金查看权限的用户开放；只在输出时附加本人的关注状态。 */
    @GetMapping("/{fundCode}/complete-detail")
    public ApiResponse<WatchlistFundDetailResponse> detail(
            @PathVariable @Pattern(regexp = "^[0-9]{6}$") String fundCode) {
        CurrentUserContext.requirePermission(PermissionCode.FUND_READ);
        var value = funds.getWatchlistFundDetail(fundCode);
        boolean followed = watchlist.findCurrentUserFollowedFundCodes(List.of(fundCode)).contains(fundCode);
        return ApiResponse.success(value.withWatchStatus(followed));
    }

    /** 份额规模为基金公共历史；不含任何用户的持有份额或申赎记录。 */
    @GetMapping("/{fundCode}/share-history")
    public ApiResponse<FundShareHistoryResponse> shares(
            @PathVariable @Pattern(regexp = "^[0-9]{6}$") String fundCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        CurrentUserContext.requirePermission(PermissionCode.FUND_READ);
        if (endDate.isBefore(startDate) || startDate.plusDays(5000).isBefore(endDate))
            throw new IllegalArgumentException("基金份额规模查询日期范围无效。");
        return ApiResponse.success(funds.getFundShareHistory(fundCode, startDate, endDate));
    }

    @GetMapping("/{fundCode}/materials")
    public ApiResponse<FundMaterialsResponse> overview(
            @PathVariable @Pattern(regexp = "^[0-9]{6}$") String fundCode,
            @RequestParam(required = false) @Pattern(regexp = "^[a-f0-9]{64}$") String reportId,
            @RequestParam(required = false) @Pattern(regexp = "^[0-9]{6}\\.(SH|SZ|BJ)$") String stockCode) {
        CurrentUserContext.requirePermission(PermissionCode.FUND_READ);
        return ApiResponse.success(materials.overview(fundCode, reportId, stockCode));
    }

    @GetMapping("/{fundCode}/materials/documents")
    public ApiResponse<FundDocumentsResponse> documents(
            @PathVariable @Pattern(regexp = "^[0-9]{6}$") String fundCode,
            @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int pageSize,
            @RequestParam(defaultValue = "all") @Pattern(regexp = "all|fund|company|news") String kind,
            @RequestParam(required = false) @Pattern(regexp = "^[0-9]{6}\\.(SH|SZ|BJ)$") String stockCode,
            @RequestParam(defaultValue = "") @Size(max = 80) String keyword,
            @RequestParam(defaultValue = "false") boolean latestOnly) {
        CurrentUserContext.requirePermission(PermissionCode.FUND_READ);
        return ApiResponse.success(materials.documents(fundCode, page, pageSize, kind, stockCode,
                keyword.strip(), latestOnly));
    }
}

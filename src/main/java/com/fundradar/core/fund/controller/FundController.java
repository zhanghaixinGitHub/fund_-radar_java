package com.fundradar.core.fund.controller;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.fund.api.FundDetailResponse;
import com.fundradar.core.fund.api.FundAnalysisSummaryResponse;
import com.fundradar.core.fund.api.FundEventPageResponse;
import com.fundradar.core.fund.api.FundFeatureStatusResponse;
import com.fundradar.core.fund.api.FundNavHistoryResponse;
import com.fundradar.core.fund.api.FundPageResponse;
import com.fundradar.core.fund.api.FundSameTypeComparisonResponse;
import com.fundradar.core.fund.api.FundSignalPageResponse;
import com.fundradar.core.fund.api.FundSummaryResponse;
import com.fundradar.core.fund.service.FundEventQueryService;
import com.fundradar.core.fund.service.FundAnalysisSummaryQueryService;
import com.fundradar.core.fund.service.FundFeatureStatusQueryService;
import com.fundradar.core.fund.service.FundQueryService;
import com.fundradar.core.fund.service.FundSignalQueryService;
import com.fundradar.core.watchlist.service.WatchlistService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 基金读模型的 Java 对外接口。
 *
 * 浏览器只访问本控制器；基金、事件与评分数据均由 Service 层受控转发到 Python AI 内部服务。
 *
 * <p>关联文档：docs_zhx/requirements/fund-radar.md；
 * docs_zhx/design/fund-radar.md；docs_zhx/testcase/fund-radar.md；
 * docs_zhx/requirements/fund-detail-expansion.md；
 * docs_zhx/design/fund-detail-expansion.md；docs_zhx/testcase/fund-detail-expansion.md。</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/funds")
public class FundController {

    private final FundQueryService fundQueryService;
    private final FundEventQueryService fundEventQueryService;
    private final FundAnalysisSummaryQueryService fundAnalysisSummaryQueryService;
    private final FundFeatureStatusQueryService fundFeatureStatusQueryService;
    private final FundSignalQueryService fundSignalQueryService;
    private final WatchlistService watchlistService;

    public FundController(
            FundQueryService fundQueryService,
            FundEventQueryService fundEventQueryService,
            FundAnalysisSummaryQueryService fundAnalysisSummaryQueryService,
            FundFeatureStatusQueryService fundFeatureStatusQueryService,
            FundSignalQueryService fundSignalQueryService,
            WatchlistService watchlistService
    ) {
        this.fundQueryService = fundQueryService;
        this.fundEventQueryService = fundEventQueryService;
        this.fundAnalysisSummaryQueryService = fundAnalysisSummaryQueryService;
        this.fundFeatureStatusQueryService = fundFeatureStatusQueryService;
        this.fundSignalQueryService = fundSignalQueryService;
        this.watchlistService = watchlistService;
    }

    /**
     * 按关键字查询基金列表；支持旧游标或页码模式，关联文档见
     * docs_zhx/requirements/fund-radar.md、docs_zhx/design/fund-radar.md、docs_zhx/testcase/fund-radar.md。
     *
     * @param keyword 基金代码或名称的搜索关键字，可选，最多 50 个字符；不传或只含空白时不按关键字筛选。
     * @param pageSize 每页返回的最大条数，默认 10，允许范围为 1～100。
     * @param cursor 兼容旧分页方式的游标，可选；查询下一页时传入上一页响应中的 nextCursor。
     *               不传或只含空白时视为未使用游标，非空白游标不能与 page 同时使用。
     * @param page 目标页码，可选，从 1 开始，允许范围为 1～10000；当前前端使用此参数翻页或跳页，
     *             不能与非空白 cursor 同时使用。
     * @param fundType 基金类型筛选条件，可选，不传时不按类型筛选；传入时只允许以下大写值：
     *                 BOND（债券型）、STOCK（股票型）、MIXED（混合型）、INDEX（指数型）、
     *                 MONEY（货币型）、QDII、FOF（基金中基金）、OTHER（其他类型）；空字符串不合法。
     */
    @GetMapping
    public ApiResponse<FundPageResponse> listFunds(
            @RequestParam(required = false) @Size(max = 50) String keyword,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(10_000) Integer page,
            @RequestParam(required = false) @jakarta.validation.constraints.Pattern(
                    regexp = "^(BOND|STOCK|MIXED|INDEX|MONEY|QDII|FOF|OTHER)$",
                    message = "基金类型筛选参数无效。"
            ) String fundType
    ) {
        CurrentUserContext.requirePermission(PermissionCode.FUND_READ);
        String normalizedCursor = cursor == null || cursor.isBlank() ? null : cursor;
        if (page != null && normalizedCursor != null) {
            throw new IllegalArgumentException("page 与 cursor 不能同时使用。");
        }
        return ApiResponse.success(withWatchStatus(
                fundQueryService.listFunds(keyword, fundType, pageSize, normalizedCursor, page)
        ));
    }

    /**
     * 查询指定六位基金代码的市场基础详情；AI 服务不可用时可安全降级为缓存结果。
     * 关联文档：docs_zhx/requirements/fund-detail-expansion.md、
     * docs_zhx/design/fund-detail-expansion.md、docs_zhx/testcase/fund-detail-expansion.md。
     */
    @GetMapping("/{fundCode}")
    public ApiResponse<FundDetailResponse> getFund(@PathVariable @Size(min = 6, max = 6) String fundCode) {
        CurrentUserContext.requirePermission(PermissionCode.FUND_READ);
        return ApiResponse.success(withWatchStatus(fundQueryService.getFund(fundCode)));
    }

    /** 查询基金历史净值；仅返回已落库的日净值，不触发数据同步或任何交易操作。 */
    @GetMapping("/{fundCode}/nav-history")
    public ApiResponse<FundNavHistoryResponse> getFundNavHistory(
            @PathVariable @Size(min = 6, max = 6) String fundCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        CurrentUserContext.requirePermission(PermissionCode.FUND_READ);
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("结束日期不得早于开始日期。");
        }
        if (startDate.plusDays(5_000).isBefore(endDate)) {
            throw new IllegalArgumentException("历史净值查询窗口过大。");
        }
        return ApiResponse.success(fundQueryService.getFundNavHistory(fundCode, startDate, endDate));
    }

    /**
     * 查询当前基金市场范围内的同类型比较；仅展示同净值日期的一月涨跌事实，不能解读为全市场排名。
     * 关联文档：docs_zhx/requirements/fund-detail-expansion.md、
     * docs_zhx/design/fund-detail-expansion.md、docs_zhx/testcase/fund-detail-expansion.md。
     */
    @GetMapping("/{fundCode}/same-type-comparison")
    public ApiResponse<FundSameTypeComparisonResponse> getFundSameTypeComparison(
            @PathVariable @Size(min = 6, max = 6) String fundCode
    ) {
        CurrentUserContext.requirePermission(PermissionCode.FUND_READ);
        return ApiResponse.success(fundQueryService.getFundSameTypeComparison(fundCode));
    }

    /** 查询指定基金的已审核可追溯关联事件，不返回资讯原文。 */
    @GetMapping("/{fundCode}/events")
    public ApiResponse<FundEventPageResponse> listEvents(
            @PathVariable @Size(min = 6, max = 6) String fundCode,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String cursor
    ) {
        CurrentUserContext.requirePermission(PermissionCode.FUND_READ);
        return ApiResponse.success(fundEventQueryService.listEvents(fundCode, pageSize, cursor));
    }

    /** 查询已落库的 M3-G1 特征状态，不触发特征构建、评分或任何交易操作。 */
    @GetMapping("/{fundCode}/feature-status")
    public ApiResponse<FundFeatureStatusResponse> getLatestFeatureStatus(
            @PathVariable @Size(min = 6, max = 6) String fundCode
    ) {
        CurrentUserContext.requirePermission(PermissionCode.FUND_READ);
        return ApiResponse.success(fundFeatureStatusQueryService.getLatestFeatureStatus(fundCode));
    }

    /** 查询指定基金已持久化的 M3 评分结果，不触发模型计算或交易操作。 */
    @GetMapping("/{fundCode}/signals")
    public ApiResponse<FundSignalPageResponse> listSignals(
            @PathVariable @Size(min = 6, max = 6) String fundCode,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String cursor
    ) {
        CurrentUserContext.requirePermission(PermissionCode.FUND_READ);
        return ApiResponse.success(fundSignalQueryService.listSignals(fundCode, pageSize, cursor));
    }

    /**
     * 查询已发布模型及关联回测摘要，不触发任何回测、评分、同步或模型状态变化。
     * 关联文档：docs_zhx/requirements/m3-decision-assistance.md、
     * docs_zhx/design/m3-decision-assistance.md、docs_zhx/testcase/m3-decision-assistance.md。
     */
    @GetMapping("/{fundCode}/analysis-summary")
    public ApiResponse<FundAnalysisSummaryResponse> getFundAnalysisSummary(
            @PathVariable @Size(min = 6, max = 6) String fundCode
    ) {
        CurrentUserContext.requirePermission(PermissionCode.FUND_READ);
        return ApiResponse.success(fundAnalysisSummaryQueryService.getFundAnalysisSummary(fundCode));
    }

    /** 在共享基金读模型返回浏览器前，按当前会话批量附加本人关注标记，绝不写入 Redis 缓存。 */
    private FundPageResponse withWatchStatus(FundPageResponse response) {
        Set<String> watchedCodes = watchlistService.findCurrentUserFollowedFundCodes(
                response.items().stream().map(FundSummaryResponse::fundCode).toList()
        );
        List<FundSummaryResponse> items = response.items().stream()
                .map(item -> new FundSummaryResponse(
                        item.fundCode(), item.fundName(), item.fundType(), item.status(), item.asOfDate(),
                        item.dayChangeRate(), item.weekChangeRate(), item.monthChangeRate(),
                        watchedCodes.contains(item.fundCode())
                ))
                .toList();
        return new FundPageResponse(
                items, response.nextCursor(), response.page(), response.pageSize(), response.totalCount(),
                response.totalPages(), response.stale(), response.cachedAt()
        );
    }

    /** 详情只追加当前用户自己的关注状态，避免页面为按钮状态拉取整份关注列表。 */
    private FundDetailResponse withWatchStatus(FundDetailResponse response) {
        boolean watched = watchlistService.findCurrentUserFollowedFundCodes(List.of(response.fundCode()))
                .contains(response.fundCode());
        return response.withClientState(watched, response.stale(), response.cachedAt());
    }
}

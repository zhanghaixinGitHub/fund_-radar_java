package com.fundradar.core.fund.controller;

import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.fund.api.FundDetailResponse;
import com.fundradar.core.fund.api.FundEventPageResponse;
import com.fundradar.core.fund.api.FundNavHistoryResponse;
import com.fundradar.core.fund.api.FundPageResponse;
import com.fundradar.core.fund.api.FundSignalPageResponse;
import com.fundradar.core.fund.service.FundEventQueryService;
import com.fundradar.core.fund.service.FundQueryService;
import com.fundradar.core.fund.service.FundSignalQueryService;
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

/**
 * 基金读模型的 Java 对外接口。
 *
 * 浏览器只访问本控制器；基金、事件与评分数据均由 Service 层受控转发到 Python AI 内部服务。
 *
 * <p>关联文档：docs_zhx/requirements/fund-radar.md；
 * docs_zhx/design/fund-radar.md；
 * docs_zhx/testcase/fund-radar.md。</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/funds")
public class FundController {

    private final FundQueryService fundQueryService;
    private final FundEventQueryService fundEventQueryService;
    private final FundSignalQueryService fundSignalQueryService;

    public FundController(
            FundQueryService fundQueryService,
            FundEventQueryService fundEventQueryService,
            FundSignalQueryService fundSignalQueryService
    ) {
        this.fundQueryService = fundQueryService;
        this.fundEventQueryService = fundEventQueryService;
        this.fundSignalQueryService = fundSignalQueryService;
    }

    /** 按关键字与游标查询基金列表；页大小限制在 1 到 100。 */
    @GetMapping
    public ApiResponse<FundPageResponse> listFunds(
            @RequestParam(required = false) @Size(max = 50) String keyword,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String cursor
    ) {
        return ApiResponse.success(fundQueryService.listFunds(keyword, pageSize, cursor));
    }

    /** 查询指定六位基金代码的详情；AI 服务不可用时可安全降级为缓存结果。 */
    @GetMapping("/{fundCode}")
    public ApiResponse<FundDetailResponse> getFund(@PathVariable @Size(min = 6, max = 6) String fundCode) {
        return ApiResponse.success(fundQueryService.getFund(fundCode));
    }

    /** 查询基金历史净值；仅返回已落库的日净值，不触发数据同步或任何交易操作。 */
    @GetMapping("/{fundCode}/nav-history")
    public ApiResponse<FundNavHistoryResponse> getFundNavHistory(
            @PathVariable @Size(min = 6, max = 6) String fundCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("结束日期不得早于开始日期。");
        }
        if (startDate.plusDays(5_000).isBefore(endDate)) {
            throw new IllegalArgumentException("历史净值查询窗口过大。");
        }
        return ApiResponse.success(fundQueryService.getFundNavHistory(fundCode, startDate, endDate));
    }

    /** 查询指定基金的已审核可追溯关联事件，不返回资讯原文。 */
    @GetMapping("/{fundCode}/events")
    public ApiResponse<FundEventPageResponse> listEvents(
            @PathVariable @Size(min = 6, max = 6) String fundCode,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String cursor
    ) {
        return ApiResponse.success(fundEventQueryService.listEvents(fundCode, pageSize, cursor));
    }

    /** 查询指定基金已持久化的 M3 评分结果，不触发模型计算或交易操作。 */
    @GetMapping("/{fundCode}/signals")
    public ApiResponse<FundSignalPageResponse> listSignals(
            @PathVariable @Size(min = 6, max = 6) String fundCode,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String cursor
    ) {
        return ApiResponse.success(fundSignalQueryService.listSignals(fundCode, pageSize, cursor));
    }
}

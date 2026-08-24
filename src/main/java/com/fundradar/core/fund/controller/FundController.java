package com.fundradar.core.fund.controller;

import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.fund.api.FundDetailResponse;
import com.fundradar.core.fund.api.FundPageResponse;
import com.fundradar.core.fund.service.FundQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public M0 mock-fund endpoints.
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

    public FundController(FundQueryService fundQueryService) {
        this.fundQueryService = fundQueryService;
    }

    @GetMapping
    public ApiResponse<FundPageResponse> listFunds(
            @RequestParam(required = false) @Size(max = 50) String keyword,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String cursor
    ) {
        return ApiResponse.success(fundQueryService.listFunds(keyword, pageSize, cursor));
    }

    @GetMapping("/{fundCode}")
    public ApiResponse<FundDetailResponse> getFund(@PathVariable @Size(min = 6, max = 6) String fundCode) {
        return ApiResponse.success(fundQueryService.getFund(fundCode));
    }
}

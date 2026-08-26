package com.fundradar.core.portfolio.controller;

import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.portfolio.api.PortfolioSnapshotResponse;
import com.fundradar.core.portfolio.service.PortfolioSnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 本机单用户持仓快照的只读接口；不提供交易、支付宝登录或截图上传能力。 */
@RestController
@RequestMapping("/api/v1/portfolio")
public class PortfolioController {

    private final PortfolioSnapshotService portfolioSnapshotService;

    public PortfolioController(PortfolioSnapshotService portfolioSnapshotService) {
        this.portfolioSnapshotService = portfolioSnapshotService;
    }

    /** 返回当前本机用户最新确认的快照，数据日期未知时由状态字段明确标识。 */
    @GetMapping("/current")
    public ApiResponse<PortfolioSnapshotResponse> getCurrentPortfolio() {
        return ApiResponse.success(portfolioSnapshotService.getCurrentUserSnapshot());
    }
}

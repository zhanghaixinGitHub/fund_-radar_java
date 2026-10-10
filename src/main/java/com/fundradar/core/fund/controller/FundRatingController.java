package com.fundradar.core.fund.controller;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.fund.api.FundRatingResponse;
import com.fundradar.core.fund.service.FundRatingQueryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 普通页面只读公共快照，鉴权在服务端；不缓存登录后的评级，撤回后立即生效。 */
@RestController
@RequestMapping("/api/v1/funds")
public class FundRatingController {
    private final FundRatingQueryService service;
    public FundRatingController(FundRatingQueryService service) { this.service = service; }

    @GetMapping("/ratings")
    public ResponseEntity<ApiResponse<FundRatingResponse.Batch>> batch(@RequestParam String fundCodes) {
        CurrentUserContext.requirePermission(PermissionCode.FUND_READ);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.batch(fundCodes)));
    }

    @GetMapping("/{fundCode}/rating")
    public ResponseEntity<ApiResponse<FundRatingResponse>> detail(@PathVariable String fundCode,
            @RequestParam(required = false) String ratingRef) {
        CurrentUserContext.requirePermission(PermissionCode.FUND_READ);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.detail(fundCode, ratingRef)));
    }
}

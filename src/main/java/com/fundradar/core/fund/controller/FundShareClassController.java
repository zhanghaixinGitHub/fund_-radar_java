package com.fundradar.core.fund.controller;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.fund.api.FundShareClassesResponse;
import com.fundradar.core.fund.service.FundShareClassQueryService;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 市场与关注详情共用公共关系，认证及基金读权限始终在服务端验证。 */
@Validated
@RestController
public class FundShareClassController {
    private final FundShareClassQueryService service;
    public FundShareClassController(FundShareClassQueryService service) { this.service = service; }

    @GetMapping("/api/v1/funds/{fundCode}/share-classes")
    public ResponseEntity<ApiResponse<FundShareClassesResponse>> read(
            @PathVariable @Pattern(regexp="^[0-9]{6}$") String fundCode) {
        CurrentUserContext.requirePermission(PermissionCode.FUND_READ);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.read(fundCode)));
    }
}

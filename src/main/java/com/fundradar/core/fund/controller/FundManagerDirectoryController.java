package com.fundradar.core.fund.controller;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.fund.api.FundManagerDirectory;
import com.fundradar.core.fund.service.FundManagerDirectoryService;
import com.fundradar.core.integration.ai.AiFundManagerDirectoryClient;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 所有经理资料由服务端认证授权，浏览器只能读取公共已发布资料，不能指定外部来源地址。 */
@Validated
@RestController
@RequestMapping("/api/v1/fund-managers")
public class FundManagerDirectoryController {
    private final FundManagerDirectoryService service;
    public FundManagerDirectoryController(FundManagerDirectoryService service) { this.service = service; }
    private void requireRead() { CurrentUserContext.requirePermission(PermissionCode.FUND_READ); }

    @GetMapping
    public ApiResponse<FundManagerDirectory.Page> list(
            @RequestParam(defaultValue="") @Size(max=128) String keyword,
            @RequestParam(defaultValue="") @Size(max=128) String company,
            @RequestParam(defaultValue="") @Pattern(regexp="^(BOND|STOCK|MIXED|INDEX|MONEY|QDII|FOF|OTHER)?$") String fundType,
            @RequestParam(defaultValue="all") @Pattern(regexp="all|current|history") String state,
            @RequestParam(defaultValue="1") @Min(1) @Max(10000) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(50) int pageSize,
            @RequestParam(required=false) UUID snapshotId) {
        requireRead();
        return ApiResponse.success(service.list(keyword, company, fundType, state, page, pageSize, snapshotId));
    }
    @GetMapping("/{id}")
    public ApiResponse<FundManagerDirectory.Detail> detail(@PathVariable UUID id, @RequestParam(required=false) UUID snapshotId) {
        requireRead(); return ApiResponse.success(service.detail(id, snapshotId));
    }
    @GetMapping("/links")
    public ApiResponse<java.util.Map<String, String>> links(@RequestParam @Pattern(regexp="^[0-9]{6}$") String fundCode) {
        requireRead(); return ApiResponse.success(service.links(fundCode));
    }
    @GetMapping("/{id}/funds")
    public ApiResponse<FundManagerDirectory.FundPage> funds(@PathVariable UUID id,
            @RequestParam(defaultValue="current") @Pattern(regexp="all|current|history|future|pending") String state,
            @RequestParam(defaultValue="1") @Min(1) @Max(10000) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(50) int pageSize, @RequestParam(required=false) UUID snapshotId) {
        requireRead(); return ApiResponse.success(service.funds(id, state, page, pageSize, snapshotId));
    }
    @GetMapping("/{id}/profile")
    public ApiResponse<FundManagerDirectory.Detail> profile(@PathVariable UUID id) {
        requireRead(); return ApiResponse.success(service.detail(id, null));
    }
    @GetMapping("/{id}/disclosures")
    public ApiResponse<List<FundManagerDirectory.Source>> disclosures(@PathVariable UUID id) {
        requireRead(); return ApiResponse.success(service.detail(id, null).disclosures());
    }
    @GetMapping("/{id}/performance")
    public ApiResponse<FundManagerDirectory.Fund> performance(@PathVariable UUID id,
            @RequestParam UUID relatedFundId, @RequestParam UUID tenureVersionId, @RequestParam(required=false) UUID snapshotId) {
        requireRead(); return ApiResponse.success(service.performance(id, relatedFundId, tenureVersionId, snapshotId));
    }
    @GetMapping("/{id}/funds/{fundId}")
    public ApiResponse<FundManagerDirectory.RelatedDetail> related(@PathVariable UUID id,
            @PathVariable UUID fundId, @RequestParam(required=false) UUID snapshotId) {
        requireRead(); return ApiResponse.success(service.related(id, fundId, snapshotId));
    }
    @ExceptionHandler(AiFundManagerDirectoryClient.DirectoryException.class)
    public ResponseEntity<ApiResponse<Void>> unavailable(AiFundManagerDirectoryClient.DirectoryException error) {
        return ResponseEntity.status(error.status()).body(ApiResponse.failure(
                error.status() == 404 ? "MANAGER_NOT_FOUND" : "SNAPSHOT_EXPIRED",
                error.status() == 404 ? "暂未收录该经理或关联基金。" : "资料已更新，请重新查看列表。"));
    }
}

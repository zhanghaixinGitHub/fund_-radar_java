package com.fundradar.core.auth.controller;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.auth.api.AdminUserPageResponse;
import com.fundradar.core.auth.api.CreateUserRequest;
import com.fundradar.core.auth.api.CurrentUserResponse;
import com.fundradar.core.auth.api.ResetPasswordRequest;
import com.fundradar.core.auth.api.TransferLegacyWatchlistRequest;
import com.fundradar.core.auth.api.UpdateUserRoleRequest;
import com.fundradar.core.auth.api.UpdateUserStatusRequest;
import com.fundradar.core.auth.service.AccountService;
import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.portfolio.api.PortfolioSnapshotResponse;
import com.fundradar.core.portfolio.service.PortfolioSnapshotService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 后台账户管理接口；每个方法先在服务端确认当前身份为 ADMIN。
 *
 * <p>关联文档：docs_zhx/requirements/user-auth-and-access.md；
 * docs_zhx/design/user-auth-and-access.md；docs_zhx/testcase/user-auth-and-access.md。</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/admin")
public class AdminUserController {

    private final AccountService accountService;
    private final PortfolioSnapshotService portfolioSnapshotService;

    public AdminUserController(AccountService accountService, PortfolioSnapshotService portfolioSnapshotService) {
        this.accountService = accountService;
        this.portfolioSnapshotService = portfolioSnapshotService;
    }

    /** 分页查询账号及其本人关注数，不返回密码哈希或会话信息。 */
    @GetMapping("/users")
    public ApiResponse<AdminUserPageResponse> listUsers(
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "页码不能小于 0。") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "每页数量至少为 1。")
            @Max(value = 100, message = "每页数量不能超过 100。") int pageSize
    ) {
        CurrentUserContext.requirePermission(PermissionCode.USER_ACCOUNT_READ);
        return ApiResponse.success(accountService.listUsers(page, pageSize));
    }

    /** 创建用户或管理员；创建人的身份和动作写入审计表。 */
    @PostMapping("/users")
    public ApiResponse<CurrentUserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.success(accountService.createUser(request, CurrentUserContext.requirePermission(PermissionCode.USER_ACCOUNT_MANAGE)));
    }

    /** 启用或停用目标账户，停用后其所有服务端会话立即撤销。 */
    @PutMapping("/users/{userId}/status")
    public ApiResponse<Void> updateUserStatus(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserStatusRequest request
    ) {
        accountService.updateUserStatus(userId, request.status(), CurrentUserContext.requirePermission(PermissionCode.USER_ACCOUNT_MANAGE));
        return ApiResponse.success(null);
    }

    /** 调整用户角色；当前会话会被撤销，以便下次登录按新角色重新加载权限。 */
    @PutMapping("/users/{userId}/role")
    public ApiResponse<Void> updateUserRole(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserRoleRequest request
    ) {
        accountService.updateUserRole(userId, request.role(), CurrentUserContext.requirePermission(PermissionCode.USER_ACCOUNT_MANAGE));
        return ApiResponse.success(null);
    }

    /** 管理员人工重置密码；重置后目标账号全部既有会话立即失效。 */
    @PostMapping("/users/{userId}/reset-password")
    public ApiResponse<Void> resetPassword(
            @PathVariable UUID userId,
            @Valid @RequestBody ResetPasswordRequest request
    ) {
        accountService.resetPassword(userId, request.newPassword(), CurrentUserContext.requirePermission(PermissionCode.USER_ACCOUNT_MANAGE));
        return ApiResponse.success(null);
    }

    /** 系统管理员查看指定用户最新确认的持仓快照，供人工受控核对个人持仓金额。 */
    @GetMapping("/users/{userId}/portfolio/current")
    public ApiResponse<PortfolioSnapshotResponse> getUserCurrentPortfolio(@PathVariable UUID userId) {
        CurrentUserContext.requirePermission(PermissionCode.PORTFOLIO_USER_READ);
        return ApiResponse.success(portfolioSnapshotService.getUserSnapshot(userId));
    }

    /** 经管理员确认后迁移旧单用户版本的历史关注列表，不自动触及提醒和持仓数据。 */
    @PostMapping("/legacy-watchlist/transfer")
    public ApiResponse<Map<String, Integer>> transferLegacyWatchlist(
            @Valid @RequestBody TransferLegacyWatchlistRequest request
    ) {
        int transferred = accountService.transferLegacyWatchlist(
                request.targetUserId(), CurrentUserContext.requirePermission(PermissionCode.LEGACY_WATCHLIST_TRANSFER)
        );
        return ApiResponse.success(Map.of("transferredCount", transferred));
    }
}

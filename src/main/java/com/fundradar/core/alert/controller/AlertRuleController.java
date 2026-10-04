package com.fundradar.core.alert.controller;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.alert.api.AlertRuleResponse;
import com.fundradar.core.alert.api.AlertRulePageResponse;
import com.fundradar.core.alert.api.UpsertAlertRuleRequest;
import com.fundradar.core.alert.service.AlertRuleService;
import com.fundradar.core.alert.service.AlertAvailabilityService;
import com.fundradar.core.alert.api.AlertAvailabilityResponse;
import com.fundradar.core.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * M3 当前用户提醒规则接口，只管理资讯型提示。
 *
 * 接口不发送交易指令、不创建订单，也不替代任何投资判断。
 *
 * <p>关联文档：docs_zhx/requirements/fund-radar.md；
 * docs_zhx/design/fund-radar.md；docs_zhx/testcase/fund-radar.md。</p>
 */
@RestController
@Validated
@RequestMapping("/api/v1/alert-rules")
public class AlertRuleController {

    private final AlertRuleService alertRuleService;
    private final AlertAvailabilityService availabilityService;

    public AlertRuleController(AlertRuleService alertRuleService, AlertAvailabilityService availabilityService) {
        this.alertRuleService = alertRuleService;
        this.availabilityService = availabilityService;
    }

    /** 仅登录且有本人提醒读取权限的用户可以查询当前消息通路；不触发检查或修改订阅。 */
    @GetMapping("/availability")
    public ApiResponse<AlertAvailabilityResponse> availability() {
        CurrentUserContext.requirePermission(PermissionCode.ALERT_RULE_SELF_READ);
        return ApiResponse.success(availabilityService.current());
    }

    /** 查询当前本地用户的全部提醒规则。 */
    @GetMapping
    public ApiResponse<List<AlertRuleResponse>> listAlertRules() {
        CurrentUserContext.requirePermission(PermissionCode.ALERT_RULE_SELF_READ);
        return ApiResponse.success(alertRuleService.listCurrentUserRules());
    }

    /** 分页读取本人规则；不传 enabled 表示全部，保留原列表接口以兼容基金详情。 */
    @GetMapping("/page")
    public ApiResponse<AlertRulePageResponse> pageAlertRules(
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码至少为 1。") int page,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页数量至少为 1。")
            @Max(value = 100, message = "每页数量不能超过 100。") int pageSize) {
        CurrentUserContext.requirePermission(PermissionCode.ALERT_RULE_SELF_READ);
        return ApiResponse.success(alertRuleService.pageCurrentUserRules(enabled, page, pageSize));
    }

    /** 按基金代码和提醒类型幂等创建或更新一条提醒规则。 */
    @PutMapping
    public ApiResponse<AlertRuleResponse> upsertAlertRule(@Valid @RequestBody UpsertAlertRuleRequest request) {
        CurrentUserContext.requirePermission(PermissionCode.ALERT_RULE_SELF_WRITE);
        return ApiResponse.success(alertRuleService.upsertCurrentUserRule(request));
    }
}

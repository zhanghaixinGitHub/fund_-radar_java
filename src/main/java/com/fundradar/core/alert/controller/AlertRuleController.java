package com.fundradar.core.alert.controller;

import com.fundradar.core.alert.api.AlertRuleResponse;
import com.fundradar.core.alert.api.UpsertAlertRuleRequest;
import com.fundradar.core.alert.service.AlertRuleService;
import com.fundradar.core.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
@RequestMapping("/api/v1/alert-rules")
public class AlertRuleController {

    private final AlertRuleService alertRuleService;

    public AlertRuleController(AlertRuleService alertRuleService) {
        this.alertRuleService = alertRuleService;
    }

    /** 查询当前本地用户的全部提醒规则。 */
    @GetMapping
    public ApiResponse<List<AlertRuleResponse>> listAlertRules() {
        return ApiResponse.success(alertRuleService.listCurrentUserRules());
    }

    /** 按基金代码和提醒类型幂等创建或更新一条提醒规则。 */
    @PutMapping
    public ApiResponse<AlertRuleResponse> upsertAlertRule(@Valid @RequestBody UpsertAlertRuleRequest request) {
        return ApiResponse.success(alertRuleService.upsertCurrentUserRule(request));
    }
}

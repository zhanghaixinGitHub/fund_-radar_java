package com.fundradar.core.advice;

import com.fundradar.core.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import static com.fundradar.core.advice.RuleTypes.*;

/** 本人持仓规则草案与确认；沿用统一会话、Origin、CSRF及服务层权限校验，userId只从会话取。 */
@RestController
@RequestMapping("/api/v1/sim-portfolios/current")
public class RuleController {
    private final RuleService service;
    public RuleController(RuleService service) { this.service=service; }
    @ModelAttribute
    public void privateResponse(HttpServletResponse response) { response.setHeader("Cache-Control","private, no-store"); }
    @GetMapping("/rule-drafts/{fundCode}") public ApiResponse<DraftView> draft(@PathVariable String fundCode) {
        return ApiResponse.success(service.draft(fundCode));
    }
    @PostMapping("/rule-drafts/{fundCode}/generate") public ApiResponse<DraftView> generate(@PathVariable String fundCode) {
        return ApiResponse.success(service.generate(fundCode));
    }
    @GetMapping("/rules/{fundCode}") public ApiResponse<RulesView> rules(@PathVariable String fundCode) {
        return ApiResponse.success(service.rules(fundCode));
    }
    @PostMapping("/rules/{fundCode}/confirm") public ApiResponse<RulesView> confirm(@PathVariable String fundCode,
            @RequestBody(required=false) ConfirmRequest request) {
        return ApiResponse.success(service.confirm(fundCode,request));
    }
    @PostMapping("/rules/{fundCode}/revoke") public ApiResponse<RulesView> revoke(@PathVariable String fundCode) {
        return ApiResponse.success(service.revoke(fundCode));
    }
}

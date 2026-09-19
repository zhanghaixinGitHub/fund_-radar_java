package com.fundradar.core.simulation;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.common.api.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.fundradar.core.simulation.SimulationTypes.*;

/** 模拟交易费率配置管理 API；费率来源为天天基金 f10 抓取初始化 + 人工维护，服务端校验 SIM_FEE_RULE_ADMIN 权限。 */
@RestController
@RequestMapping("/api/v1/admin/sim-fee-rules")
@ConditionalOnProperty(name="simulation.enabled",havingValue="true",matchIfMissing=true)
public class SimulationFeeController {
    private final SimulationFeeService fees;
    public SimulationFeeController(SimulationFeeService fees) { this.fees=fees; }

    /**
     * 分页查询费率规则（含已终止的历史版本），可按基金代码过滤；查询走 fund_code 索引。
     *
     * @需求文档 docs_zhx/requirements/sim-fee-and-nav-settlement.md
     * @设计文档 docs_zhx/design/sim-fee-and-nav-settlement.md
     * @测试文档 docs_zhx/textcase/sim-fee-and-nav-settlement.md
     */
    @GetMapping
    public ApiResponse<Page<FeeRuleRow>> list(@RequestParam(required=false) String fundCode,
                                              @RequestParam(defaultValue="1") int page,
                                              @RequestParam(defaultValue="20") int pageSize) {
        CurrentUserContext.requirePermission(PermissionCode.SIM_FEE_RULE_ADMIN);
        if (page<1 || pageSize<1 || pageSize>100) throw new SimulationException("SIM_PAGE_INVALID","分页参数不合法。");
        return ApiResponse.success(fees.pageRules(fundCode,page,pageSize));
    }

    /**
     * 人工维护单条费率；乐观锁版本不一致时拒绝更新，避免两个管理员互相覆盖。
     *
     * @需求文档 docs_zhx/requirements/sim-fee-and-nav-settlement.md
     * @设计文档 docs_zhx/design/sim-fee-and-nav-settlement.md
     * @测试文档 docs_zhx/textcase/sim-fee-and-nav-settlement.md
     */
    @PutMapping("/{ruleId}")
    public ApiResponse<FeeRuleRow> update(@PathVariable long ruleId,@RequestBody FeeUpdateRequest request) {
        CurrentUserContext.requirePermission(PermissionCode.SIM_FEE_RULE_ADMIN);
        return ApiResponse.success(fees.updateRule(ruleId,request.rate(),request.version()));
    }

    /**
     * 按天天基金 f10 抓取刷新单只基金的申赎费率，返回刷新后生效中的规则列表。
     *
     * @需求文档 docs_zhx/requirements/sim-fee-and-nav-settlement.md
     * @设计文档 docs_zhx/design/sim-fee-and-nav-settlement.md
     * @测试文档 docs_zhx/textcase/sim-fee-and-nav-settlement.md
     */
    @PostMapping("/refresh/{fundCode}")
    public ApiResponse<List<FeeRuleRow>> refresh(@PathVariable String fundCode) {
        CurrentUserContext.requirePermission(PermissionCode.SIM_FEE_RULE_ADMIN);
        return ApiResponse.success(fees.refreshFund(fundCode));
    }

    /**
     * 全量初始化：并发抓取模拟范围内全部基金的费率并落库；单只失败只记录不中断，返回汇总结果。
     *
     * @需求文档 docs_zhx/requirements/sim-fee-and-nav-settlement.md
     * @设计文档 docs_zhx/design/sim-fee-and-nav-settlement.md
     * @测试文档 docs_zhx/textcase/sim-fee-and-nav-settlement.md
     */
    @PostMapping("/init-all")
    public ApiResponse<FeeInitResult> initAll() {
        CurrentUserContext.requirePermission(PermissionCode.SIM_FEE_RULE_ADMIN);
        return ApiResponse.success(fees.initAll());
    }
}

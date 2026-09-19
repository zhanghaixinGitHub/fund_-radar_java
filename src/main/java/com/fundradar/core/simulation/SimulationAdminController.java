package com.fundradar.core.simulation;

import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.common.api.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import static com.fundradar.core.simulation.SimulationTypes.RecurringRunResult;

/** 管理员模拟账户运维 API；手动补跑到期定投，沿用同步管理权限并在控制器再次校验。 */
@RestController
@RequestMapping("/api/v1/admin/sim-recurring-plans")
@ConditionalOnProperty(name="simulation.enabled",havingValue="true",matchIfMissing=true)
public class SimulationAdminController {
    private final SimulationScheduler scheduler;
    public SimulationAdminController(SimulationScheduler scheduler) { this.scheduler=scheduler; }
    /** 与每分钟定时任务共用同一执行链路和数据库锁；只处理当前到期期次，不补造过去错过的买单。 */
    @PostMapping("/run-due")
    public ApiResponse<RecurringRunResult> runDue() {
        CurrentUserContext.requirePermission(PermissionCode.SYNC_JOB_START);
        return ApiResponse.success(scheduler.runDueManually());
    }
}

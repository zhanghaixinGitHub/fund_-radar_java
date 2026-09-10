package com.fundradar.core.simulation;

import com.fundradar.core.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;
import static com.fundradar.core.simulation.SimulationTypes.*;
import static com.fundradar.core.simulation.SimulationService.*;

/** 本人模拟组合 API；沿用统一会话、CSRF 和异常处理，服务层再次校验权限与归属。 */
@RestController
@RequestMapping("/api/v1/sim-portfolios/current")
public class SimulationController {
    private final SimulationService service;
    public SimulationController(SimulationService service) { this.service=service; }
    @GetMapping public ApiResponse<Overview> overview() { return ApiResponse.success(service.overview()); }
    @GetMapping("/preview/{fundCode}") public ApiResponse<Preview> preview(@PathVariable String fundCode) { return ApiResponse.success(service.preview(fundCode)); }
    @PostMapping("/orders") public ApiResponse<Order> place(@Valid @RequestBody OrderRequest request) { return ApiResponse.success(service.place(request)); }
    @PostMapping("/orders/{id}/cancel") public ApiResponse<Order> cancel(@PathVariable UUID id) { return ApiResponse.success(service.cancel(id)); }
    @GetMapping("/orders") public ApiResponse<Page<Order>> orders(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,
                                                                @RequestParam(required=false) String fundCode) { return ApiResponse.success(service.orders(page,pageSize,fundCode)); }
    @GetMapping("/ledger") public ApiResponse<Page<Map<String,Object>>> ledger(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,
                                                                            @RequestParam(required=false) String fundCode) { return ApiResponse.success(service.ledger(page,pageSize,fundCode)); }
    @GetMapping("/performance/{fundCode}") public ApiResponse<List<Daily>> performance(@PathVariable String fundCode,@RequestParam LocalDate startDate,
                                                                                    @RequestParam LocalDate endDate) { return ApiResponse.success(service.performance(fundCode,startDate,endDate)); }
    @GetMapping("/recurring-plans") public ApiResponse<List<Plan>> plans() { return ApiResponse.success(service.plans()); }
    @PostMapping("/recurring-plans/preview") public ApiResponse<Map<String,LocalDate>> planPreview(@Valid @RequestBody PlanRequest request) { return ApiResponse.success(service.planPreview(request)); }
    @PostMapping("/recurring-plans") public ApiResponse<Plan> createPlan(@Valid @RequestBody PlanRequest request) { return ApiResponse.success(service.savePlan(null,request)); }
    @PutMapping("/recurring-plans/{id}") public ApiResponse<Plan> editPlan(@PathVariable UUID id,@Valid @RequestBody PlanRequest request) { return ApiResponse.success(service.savePlan(id,request)); }
    @PostMapping("/recurring-plans/{id}/actions") public ApiResponse<Plan> action(@PathVariable UUID id,@Valid @RequestBody PlanAction request) { return ApiResponse.success(service.planAction(id,request)); }
    @GetMapping("/recurring-plans/{id}/executions") public ApiResponse<Page<Period>> periods(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,
                                                                                         @RequestParam(defaultValue="20") int pageSize) { return ApiResponse.success(service.periods(id,page,pageSize)); }
}

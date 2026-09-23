package com.fundradar.core.prediction;

import com.fundradar.core.advice.StrategyResearchService;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.common.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 管理员研究权限独立于普通关注权限；客户端不能提供可执行文件或模型路径。 */
@RestController
@RequestMapping("/api/v1/admin/prediction-research")
public class PredictionResearchController {
    private final MultiPredictionClient client;private final StrategyResearchService strategy;
    public PredictionResearchController(MultiPredictionClient client,StrategyResearchService strategy) {this.client=client;this.strategy=strategy;}
    private void permit() {CurrentUserContext.requirePermission(PermissionCode.RESEARCH_RUN_ADMIN);}
    @PostMapping public ApiResponse<?> create(@RequestBody Map<String,Object> spec) {permit();return ok(client.post("/research",spec));}
    @GetMapping("/{id}") public ApiResponse<?> read(@PathVariable UUID id) {permit();return ok(client.get("/research/"+id));}
    @PostMapping("/{id}/cancel") public ApiResponse<?> cancel(@PathVariable UUID id) {permit();return ok(client.post("/research/"+id+"/cancel",Map.of()));}
    @PostMapping("/{id}/resume") public ApiResponse<?> resume(@PathVariable UUID id) {permit();return ok(client.post("/research/"+id+"/resume",Map.of()));}
    @PostMapping("/strategy") public ApiResponse<?> replay(@RequestBody StrategyResearchService.Request request) {return ok(strategy.run(request));}
    @GetMapping("/strategy/{id}") public ApiResponse<?> strategy(@PathVariable UUID id) {return ok(strategy.read(id));}
    private static ApiResponse<?> ok(Object value) { return ApiResponse.success(com.fundradar.core.prediction.PredictionWebData.of(value)); }
}

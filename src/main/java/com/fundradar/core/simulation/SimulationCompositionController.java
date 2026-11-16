package com.fundradar.core.simulation;

import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.simulation.SimulationCompositionTypes.Composition;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 本人只读持仓构成；服务层从会话确定账户并再次校验权限。 */
@RestController
@RequestMapping("/api/v1/sim-portfolios/current/composition")
public class SimulationCompositionController {
    private final SimulationCompositionService service;

    public SimulationCompositionController(SimulationCompositionService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<Composition> composition() {
        return ApiResponse.success(service.composition());
    }
}

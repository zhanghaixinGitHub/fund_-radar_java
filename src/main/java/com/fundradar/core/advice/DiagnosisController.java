package com.fundradar.core.advice;

import com.fundradar.core.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.fundradar.core.advice.DiagnosisTypes.*;

/** 本人持仓诊断；沿用统一会话、Origin、CSRF及服务层权限校验，userId只从会话取。 */
@RestController
@RequestMapping("/api/v1/sim-portfolios/current/diagnosis")
public class DiagnosisController {
    private final DiagnosisService service;
    public DiagnosisController(DiagnosisService service) { this.service=service; }
    @ModelAttribute
    public void privateResponse(HttpServletResponse response) { response.setHeader("Cache-Control","private, no-store"); }
    @GetMapping public ApiResponse<List<Summary>> latest() { return ApiResponse.success(service.latest()); }
    @GetMapping("/{fundCode}") public ApiResponse<History> history(@PathVariable String fundCode,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return ApiResponse.success(service.history(fundCode,page,pageSize));
    }
}

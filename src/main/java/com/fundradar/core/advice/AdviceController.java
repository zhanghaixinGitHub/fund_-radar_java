package com.fundradar.core.advice;

import com.fundradar.core.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static com.fundradar.core.advice.AdviceTypes.*;

/** 本人持仓建议；写入口沿用统一会话、Origin、CSRF及服务层权限校验。 */
@RestController
@RequestMapping("/api/v1/sim-portfolios/current/advice")
public class AdviceController {
    private final AdviceService service;
    public AdviceController(AdviceService service) { this.service=service; }
    @ModelAttribute
    public void privateResponse(HttpServletResponse response) { response.setHeader("Cache-Control","private, no-store"); }
    @GetMapping public ApiResponse<List<Summary>> latest() { return ApiResponse.success(service.latest()); }
    @GetMapping("/{fundCode}") public ApiResponse<History> history(@PathVariable String fundCode,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,
            @RequestParam(required=false) LocalDate startDate,@RequestParam(required=false) LocalDate endDate,
            @RequestParam(defaultValue=AdvicePolicy.VERSION) String ruleVersion) {
        return ApiResponse.success(service.history(fundCode,page,pageSize,startDate,endDate,ruleVersion));
    }
    @GetMapping("/{fundCode}/reports/{id}") public ApiResponse<Detail> detail(@PathVariable String fundCode,@PathVariable UUID id) {
        return ApiResponse.success(service.detail(fundCode,id));
    }
    @PostMapping("/{fundCode}/generate") public ApiResponse<Detail> generate(@PathVariable String fundCode) {
        return ApiResponse.success(service.generate(fundCode));
    }
}

package com.fundradar.core.direction1d;

import com.fundradar.core.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

/** 独立1日浏览器契约；统一会话/Origin/CSRF拦截器继续执行，绝不接收userId。 */
@RestController
@RequestMapping("/api/v1/watchlist")
public class Direction1dController {
    private final Direction1dService service;
    private final Direction1dStatistics statistics;
    public Direction1dController(Direction1dService service,Direction1dStatistics statistics) { this.service=service; this.statistics=statistics; }
    @ModelAttribute public void privateResponse(HttpServletResponse response) { response.setHeader("Cache-Control","no-store, private"); }
    @GetMapping("/prediction-1d/status") public ApiResponse<?> status() { return ApiResponse.success(service.status()); }
    @PutMapping("/prediction-1d/subscription") public ApiResponse<?> subscription(@RequestBody Map<String,Object> body) {
        if(!body.keySet().equals(Set.of("enabled")) || !(body.get("enabled") instanceof Boolean)) throw new IllegalArgumentException("INVALID_SUBSCRIPTION");
        return ApiResponse.success(service.subscription((Boolean)body.get("enabled")));
    }
    @GetMapping("/prediction-1d/coverage") public ApiResponse<?> coverage(@RequestParam(required=false) String keyword,
        @RequestParam(required=false) String fundType,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return ApiResponse.success(service.coverage(keyword,fundType,page,pageSize));
    }
    @GetMapping("/{fundCode}/prediction-1d") public ApiResponse<?> current(@PathVariable String fundCode) { return ApiResponse.success(service.current(fundCode)); }
    @PostMapping("/{fundCode}/prediction-1d/generate") public ResponseEntity<?> generate(@PathVariable String fundCode,@RequestBody(required=false) Map<String,Object> body) {
        if(body!=null&&!body.isEmpty()) throw new IllegalArgumentException("NO_CLIENT_PARAMETERS");
        return ResponseEntity.accepted().body(ApiResponse.success(service.generate(fundCode)));
    }
    @GetMapping("/prediction-1d/history") public ApiResponse<?> history(@RequestParam(required=false) String fundCode,
        @RequestParam(defaultValue="2021-01-01") LocalDate startDate,@RequestParam(defaultValue="2026-12-31") LocalDate endDate,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,
        @RequestParam(required=false) LocalDate beforeDate,@RequestParam(required=false) UUID beforeId,
        @RequestParam(defaultValue="") String branch,@RequestParam(defaultValue="") String assessment) {
        return ApiResponse.success(service.history(fundCode,startDate,endDate,page,pageSize,beforeDate,beforeId,branch,assessment));
    }
    @GetMapping("/prediction-1d/reports/{forecastId}") public ApiResponse<?> detail(@PathVariable UUID forecastId) { return ApiResponse.success(service.detail(forecastId)); }
    @GetMapping("/prediction-1d/metrics") public ApiResponse<?> metrics(@RequestParam(defaultValue="2021-01-01") LocalDate startDate,
            @RequestParam(defaultValue="2026-12-31") LocalDate endDate,@RequestParam(defaultValue="FIRST_OBSERVED") String labelBasis) {
        return ApiResponse.success(statistics.read(service.user(false),startDate,endDate,labelBasis));
    }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException error) {
        String code=error.getMessage(); boolean conflict=Set.of("MISSED_DEADLINE","SUBSCRIPTION_DISABLED","BACKEND_DISABLED","CLOCK_SKEW").contains(code);
        return ResponseEntity.status(conflict?409:400).body(ApiResponse.failure("DIRECTION_1D_REJECTED",conflict?code:"请求参数或实验数据未通过校验。"));
    }
    @ExceptionHandler(NoSuchElementException.class) public ResponseEntity<?> missing() { return ResponseEntity.status(404).body(ApiResponse.failure("NOT_FOUND","本人没有该记录。")); }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> unavailable() { return ResponseEntity.status(503).body(ApiResponse.failure("DIRECTION_1D_UNAVAILABLE","1日实验服务暂不可用，已留档记录保留。")); }
}

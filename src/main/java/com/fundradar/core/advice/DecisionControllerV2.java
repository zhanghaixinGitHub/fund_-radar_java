package com.fundradar.core.advice;

import com.fundradar.core.common.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/portfolio")
public class DecisionControllerV2 {
    private final DecisionServiceV2 service;
    private final HoldingAdviceService holdings;
    @ModelAttribute public void privateResponse(jakarta.servlet.http.HttpServletResponse response) {response.setHeader("Cache-Control","no-store, private");}
    @GetMapping("/decisions/latest") public ApiResponse<?> latestMine() {return ok(holdings.latestMine());}
    public DecisionControllerV2(DecisionServiceV2 service,HoldingAdviceService holdings) { this.service=service;this.holdings=holdings; }
    @GetMapping("/funds/{code}/decision") public ApiResponse<?> latest(@PathVariable String code) { return ok(holdings.latest(code)); }
    @PostMapping("/funds/{code}/decision/generate") public ApiResponse<?> generate(@PathVariable String code) { return ok(holdings.generate(code)); }
    @GetMapping("/funds/{code}/decision/history") public ApiResponse<?> history(@PathVariable String code,
      @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue=HoldingAdvicePolicy.VERSION) String version) { return ok(Set.of(HoldingAdvicePolicy.VERSION,HoldingAdvicePolicy.PREVIOUS_VERSION).contains(version)?holdings.history(code,page,version):service.history(code,page,version)); }
    @GetMapping("/funds/{code}/decision/reports/{id}") public ApiResponse<?> report(@PathVariable String code,@PathVariable UUID id) { return ok(service.report(code,id)); }
    @GetMapping("/funds/{code}/decision/outcomes") public ApiResponse<?> outcomes(@PathVariable String code,@RequestParam(defaultValue="1") int page,
      @RequestParam(defaultValue=HoldingAdvicePolicy.VERSION) String version) {
        if(Set.of(HoldingAdvicePolicy.VERSION,HoldingAdvicePolicy.PREVIOUS_VERSION).contains(version)) {holdings.history(code,page,version);return ok(Map.of());}
        return ok(service.outcomes(code,page,version));}
    @GetMapping("/strategy-preference") public ApiResponse<?> preference() { return ok(service.preference()); }
    @PostMapping("/strategy-preference") public ApiResponse<?> preference(@RequestBody Map<String,String> body) {
        if(!body.keySet().equals(Set.of("preference"))) throw new IllegalArgumentException();
        return ok(service.savePreference(body.get("preference")));
    }
    @ExceptionHandler(NoSuchElementException.class) public ResponseEntity<?> missing() { return ResponseEntity.status(404).body(ApiResponse.failure("NOT_FOUND","本人没有该持仓或报告。")); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(ApiResponse.failure("INVALID_REQUEST","当前持仓或请求状态已变化，请重新读取后再试。")); }
    private static ApiResponse<?> ok(Object value) { return ApiResponse.success(com.fundradar.core.prediction.PredictionWebData.of(value)); }
}

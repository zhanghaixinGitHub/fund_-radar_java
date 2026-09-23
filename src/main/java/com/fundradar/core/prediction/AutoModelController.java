package com.fundradar.core.prediction;

import com.fundradar.core.common.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** 普通摘要限定本人关注；技术详情由服务层再次校验管理员研究权限。 */
@RestController
@RequestMapping("/api/v1/predictions/automatic")
public class AutoModelController {
    private final AutoModelService service;
    private final com.fundradar.core.advice.IssuedAdviceEffectService adviceEffects;
    public AutoModelController(AutoModelService service,com.fundradar.core.advice.IssuedAdviceEffectService adviceEffects) {this.service=service;this.adviceEffects=adviceEffects;}
    @ModelAttribute public void privateResponse(jakarta.servlet.http.HttpServletResponse response) {response.setHeader("Cache-Control","no-store, private");}
    @GetMapping("/summary") public ApiResponse<?> summary() {return ok(service.summary());}
    @GetMapping("/effects") public ApiResponse<?> effects() {return ok(service.effects());}
    @PostMapping("/advice-effects") public ApiResponse<?> adviceEffects(@RequestBody com.fundradar.core.advice.IssuedAdviceEffectService.Request request) {return ok(adviceEffects.read(request));}
    @GetMapping("/cycles") public ApiResponse<?> cycles(@RequestParam(required=false) String before,@RequestParam(required=false) UUID beforeId) {return ok(service.cycles(before,beforeId));}
    @GetMapping("/cycles/{id}") public ApiResponse<?> cycle(@PathVariable UUID id) {return ok(service.cycle(id));}
    @PostMapping("/cycles/{id}/cancel") public ApiResponse<?> cancel(@PathVariable UUID id) {return ok(service.action(id,"cancel"));}
    @PostMapping("/cycles/{id}/resume") public ApiResponse<?> resume(@PathVariable UUID id) {return ok(service.action(id,"resume"));}
    @ExceptionHandler(java.util.NoSuchElementException.class) public org.springframework.http.ResponseEntity<?> missing() {
        return org.springframework.http.ResponseEntity.status(404).body(ApiResponse.failure("NOT_FOUND","本人没有该基金或记录。"));
    }
    @ExceptionHandler(IllegalArgumentException.class) public org.springframework.http.ResponseEntity<?> invalid() {
        return org.springframework.http.ResponseEntity.badRequest().body(ApiResponse.failure("INVALID_REQUEST","请求参数未通过校验。"));
    }
    @ExceptionHandler(MultiPredictionClient.PredictionServiceFailure.class) public org.springframework.http.ResponseEntity<?> unavailable(MultiPredictionClient.PredictionServiceFailure error) {
        return org.springframework.http.ResponseEntity.status(503).body(ApiResponse.failure(error.detail().code(),error.detail().summary()+" 请求号："+error.detail().traceId()));
    }
    private static ApiResponse<?> ok(Object value) {return ApiResponse.success(PredictionWebData.of(value));}
}

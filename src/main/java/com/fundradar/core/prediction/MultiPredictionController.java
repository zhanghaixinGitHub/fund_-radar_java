package com.fundradar.core.prediction;

import com.fundradar.core.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 统一会话、Origin/CSRF与服务层所有权校验继续生效，不接收userId、模型路径或历史时间。 */
@RestController
@RequestMapping("/api/v1")
public class MultiPredictionController {
    private final MultiPredictionService service;
    public MultiPredictionController(MultiPredictionService service) { this.service=service; }
    @ModelAttribute public void privateResponse(HttpServletResponse response) { response.setHeader("Cache-Control","no-store, private"); }
    @GetMapping("/watchlist/{code}/predictions") public ApiResponse<?> current(@PathVariable String code) { return ok(service.current(code)); }
    @GetMapping("/watchlist/{code}/predictions/history") public ApiResponse<?> history(@PathVariable String code,@RequestParam(required=false) String before,@RequestParam(required=false) UUID beforeId) { return ok(service.history(code,before,beforeId)); }
    @PostMapping("/watchlist/{code}/predictions/generate") public ApiResponse<?> generate(@PathVariable String code,@RequestBody(required=false) Map<String,Object> body) { empty(body); return ok(service.generate(code)); }
    @PostMapping("/watchlist/predictions/generate") public ApiResponse<?> generateAll(@RequestBody(required=false) Map<String,Object> body) { empty(body); return ok(service.generateMine()); }
    @GetMapping("/watchlist/predictions/tasks/{id}") public ApiResponse<?> task(@PathVariable UUID id) { return ok(service.task(id,false)); }
    @GetMapping("/watchlist/predictions/tasks/latest") public ApiResponse<?> latestTask() {return ok(service.latestTask());}
    @PostMapping("/watchlist/predictions/tasks/{id}/retry") public ApiResponse<?> retry(@PathVariable UUID id) { return ok(service.task(id,true)); }
    @GetMapping("/admin/model-routes") public ApiResponse<?> models() { return ok(service.models()); }
    private void empty(Map<String,Object> body) { if(body!=null&&!body.isEmpty()) throw new IllegalArgumentException("生成无需客户端时间或模型参数"); }
    @ExceptionHandler(NoSuchElementException.class) public ResponseEntity<?> missing() { return ResponseEntity.status(404).body(ApiResponse.failure("NOT_FOUND","本人没有该基金或任务。")); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(ApiResponse.failure("INVALID_REQUEST","请求参数未通过校验。")); }
    @ExceptionHandler(MultiPredictionClient.PredictionServiceFailure.class) public ResponseEntity<?> unavailable(MultiPredictionClient.PredictionServiceFailure error) {
        return ResponseEntity.status(503).body(ApiResponse.failure(error.detail().code(),error.detail().summary()+" 请求号："+error.detail().traceId()));
    }
    private static ApiResponse<?> ok(Object value) { return ApiResponse.success(com.fundradar.core.prediction.PredictionWebData.of(value)); }
}

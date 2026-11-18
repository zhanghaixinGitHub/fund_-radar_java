package com.fundradar.core.advice;

import com.fundradar.core.auth.service.AccessDeniedException;
import com.fundradar.core.integration.ai.AiServiceProperties;
import com.fundradar.core.prediction.PredictionWebData;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** 受控单只留档入口；没有调度或交易写入，拒绝浏览器跨站调用。 */
@RestController
@RequestMapping("/internal/v1/holding-advice")
public class InternalHoldingAdviceController {
    private final HoldingAdviceService service;private final AiServiceProperties config;
    public InternalHoldingAdviceController(HoldingAdviceService service,AiServiceProperties config) {this.service=service;this.config=config;}
    @PostMapping("/users/{owner}/funds/{code}/generate")
    public Object generate(HttpServletRequest request,@PathVariable UUID owner,@PathVariable String code) {
        authorize(request);
        return PredictionWebData.of(service.generateFor(owner,code));
    }
    @GetMapping("/users/{owner}/funds/{code}/current")
    public Object current(HttpServletRequest request,@PathVariable UUID owner,@PathVariable String code) {
        authorize(request);
        return PredictionWebData.of(service.currentFor(owner,code));
    }
    private void authorize(HttpServletRequest request) {
        String expected=config.getToken(),provided=request.getHeader("X-Service-Token");
        if(request.getHeader("Origin")!=null || request.getHeader("Sec-Fetch-Site")!=null
                || expected==null || expected.isBlank() || provided==null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),provided.getBytes(StandardCharsets.UTF_8))) throw new AccessDeniedException();
    }
}

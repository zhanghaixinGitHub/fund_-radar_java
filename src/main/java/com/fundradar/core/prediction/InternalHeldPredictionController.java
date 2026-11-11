package com.fundradar.core.prediction;

import com.fundradar.core.auth.service.AccessDeniedException;
import com.fundradar.core.integration.ai.AiServiceProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** 仅服务令牌可以把本期原预测归档到实际持仓账号；不启动预测或扩展到其他账号。 */
@RestController
@RequestMapping("/internal/v1/predictions/sync")
public class InternalHeldPredictionController {
    private final MultiPredictionService service;
    private final AiServiceProperties properties;
    public InternalHeldPredictionController(MultiPredictionService service,AiServiceProperties properties) {
        this.service=service; this.properties=properties;
    }
    public record Request(UUID predictionId,LocalDate targetDate) {}
    @PostMapping("/users/{owner}/{code}/archive")
    public Map<String,Object> archive(HttpServletRequest request,@PathVariable UUID owner,@PathVariable String code,@RequestBody Request body) {
        String expected=properties.getToken(),supplied=request.getHeader("X-Service-Token");
        if(request.getHeader("Origin")!=null || request.getHeader("Sec-Fetch-Site")!=null
                || expected==null || expected.isBlank() || supplied==null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            throw new AccessDeniedException();
        if(!code.matches("[0-9]{6}") || body.predictionId()==null || body.targetDate()==null)
            throw new IllegalArgumentException("INVALID_PREDICTION_SCOPE");
        return service.archiveHeld(owner,code,body.predictionId(),body.targetDate());
    }
}

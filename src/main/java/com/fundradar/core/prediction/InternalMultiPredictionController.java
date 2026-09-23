package com.fundradar.core.prediction;

import com.fundradar.core.auth.service.AccessDeniedException;
import com.fundradar.core.integration.ai.AiServiceProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Python同步完成后的留档回调；服务令牌与浏览器拒绝策略和已有内部接口一致。 */
@RestController
@RequestMapping("/internal/v1/multi-predictions/sync")
public class InternalMultiPredictionController {
    private final MultiPredictionPipeline pipeline;private final AiServiceProperties properties;
    public InternalMultiPredictionController(MultiPredictionPipeline pipeline,AiServiceProperties properties) {this.pipeline=pipeline;this.properties=properties;}
    @PostMapping("/finalize")
    public Map<String,Object> finish(HttpServletRequest request,@RequestBody Map<String,String> body) {
        String expected=properties.getToken(),supplied=request.getHeader("X-Service-Token");
        if(expected==null||expected.isBlank()||supplied==null||request.getHeader("Origin")!=null||request.getHeader("Sec-Fetch-Site")!=null
                ||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            throw new AccessDeniedException();
        if(!body.keySet().equals(Set.of("taskId"))) throw new IllegalArgumentException("回调范围不正确");
        return pipeline.archive(UUID.fromString(body.get("taskId")),true);
    }
}

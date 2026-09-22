package com.fundradar.core.direction1d;

import com.fundradar.core.auth.service.AccessDeniedException;
import com.fundradar.core.integration.ai.AiServiceProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** Python 同步线程回调 Java 留档的专用边界；复用已有服务令牌，拒绝浏览器直接调用。 */
@RestController
@RequestMapping("/internal/v1/direction-1d/sync")
public class InternalDirection1dSyncController {
    private final Direction1dBatchService batch;
    private final AiServiceProperties properties;
    public InternalDirection1dSyncController(Direction1dBatchService batch,AiServiceProperties properties) {
        this.batch=batch; this.properties=properties;
    }

    @GetMapping("/fund-codes")
    public List<String> fundCodes(HttpServletRequest request,@RequestParam(defaultValue="") String after) {
        authenticate(request);
        if(!after.isEmpty() && !after.matches("[0-9]{6}")) throw new IllegalArgumentException("INVALID_CURSOR");
        return batch.fundCodes(after);
    }

    @GetMapping("/window")
    public Map<String,Object> window(HttpServletRequest request) { authenticate(request); return batch.window(); }

    @PostMapping("/{code}")
    public Map<String,Object> generate(HttpServletRequest request,@PathVariable String code,@RequestBody Map<String,String> body) {
        authenticate(request);
        if(!body.keySet().equals(Set.of("targetNavDate"))) throw new IllegalArgumentException("INVALID_PREDICTION_SCOPE");
        return batch.generate(code,LocalDate.parse(body.get("targetNavDate")));
    }

    private void authenticate(HttpServletRequest request) {
        String expected=properties.getToken(),supplied=request.getHeader("X-Service-Token");
        if(request.getHeader("Origin")!=null || request.getHeader("Sec-Fetch-Site")!=null
                || expected==null || expected.isBlank() || supplied==null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            throw new AccessDeniedException();
    }
}

package com.fundradar.core.direction1d;

import com.fundradar.core.auth.service.AccessDeniedException;
import com.fundradar.core.integration.ai.AiServiceProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/internal/v1/direction-1d/sync/users/{owner}/outcomes")
public class InternalDirection1dOutcomeController {
    private final Direction1dOutcomeService service;
    private final AiServiceProperties properties;
    public InternalDirection1dOutcomeController(Direction1dOutcomeService service,AiServiceProperties properties) {
        this.service=service;this.properties=properties;
    }
    @PostMapping("/reconcile")
    public Map<String,Object> reconcile(HttpServletRequest request,@PathVariable UUID owner,
            @RequestBody Map<String,String> body) {
        return reconcile(request,owner,body,false);
    }
    @PostMapping("/archive/reconcile")
    public Map<String,Object> archiveReconcile(HttpServletRequest request,@PathVariable UUID owner,
            @RequestBody Map<String,String> body) {
        return reconcile(request,owner,body,true);
    }
    private Map<String,Object> reconcile(HttpServletRequest request,UUID owner,Map<String,String> body,boolean archive) {
        authenticate(request);
        if(!body.keySet().equals(Set.of("dueBefore"))&&!body.keySet().equals(Set.of("dueBefore","after")))
            throw new IllegalArgumentException("INVALID_OUTCOME_REQUEST");
        return service.reconcile(owner,LocalDate.parse(body.get("dueBefore")),
                body.containsKey("after")?UUID.fromString(body.get("after")):null,archive);
    }
    @GetMapping("/status")
    public Map<String,Object> status(HttpServletRequest request,@PathVariable UUID owner,@RequestParam LocalDate dueBefore) {
        authenticate(request);
        return service.status(owner,dueBefore);
    }
    @GetMapping("/archive/status")
    public Map<String,Object> archiveStatus(HttpServletRequest request,@PathVariable UUID owner,@RequestParam LocalDate dueBefore) {
        authenticate(request);
        return service.archiveStatus(owner,dueBefore);
    }
    @GetMapping("/archive/fund-codes")
    public List<String> archiveCodes(HttpServletRequest request,@PathVariable UUID owner) {
        authenticate(request);
        return service.archiveCodes(owner);
    }
    private void authenticate(HttpServletRequest request) {
        String expected=properties.getToken(),supplied=request.getHeader("X-Service-Token");
        if(request.getHeader("Origin")!=null||request.getHeader("Sec-Fetch-Site")!=null
                ||expected==null||expected.isBlank()||supplied==null
                ||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            throw new AccessDeniedException();
    }
}

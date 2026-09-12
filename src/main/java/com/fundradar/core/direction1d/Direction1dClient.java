package com.fundradar.core.direction1d;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.integration.ai.AiServiceProperties;
import com.fundradar.core.common.trace.TraceContext;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.time.Duration;
import java.util.*;

/** 沿用既有服务Token；只传公共代码及注册作业编号，不传账户、持仓或浏览器cookie。 */
@Service
public class Direction1dClient {
    private final RestClient client; private final AiServiceProperties properties; private final ObjectMapper json;
    public Direction1dClient(RestClient.Builder builder,AiServiceProperties properties,ObjectMapper json) {
        this.properties=properties; this.json=json;
        var factory=new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(properties.getConnectTimeout());
        factory.setReadTimeout(Duration.ofSeconds(30));
        client=builder.baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
    }
    public JsonNode get(String path) { return request(path,null); }
    public JsonNode post(String path,Object body) { return request(path,body); }
    private JsonNode request(String path,Object body) {
        try {
            String result=body==null ? client.get().uri("/internal/v1/direction-1d"+path)
                .header("X-Service-Token",properties.getToken()).header("X-Trace-Id",TraceContext.getTraceId()).retrieve().body(String.class)
                : client.post().uri("/internal/v1/direction-1d"+path).header("X-Service-Token",properties.getToken())
                .header("X-Trace-Id",TraceContext.getTraceId()).body(body).retrieve().body(String.class);
            if(result==null || result.length()>1_000_000) throw new IllegalStateException("INVALID_RESPONSE");
            return json.readTree(result);
        } catch(Exception e) { throw new IllegalStateException("1日内部服务暂不可用",e); }
    }
    public JsonNode coverage(List<String> codes) {
        JsonNode result=post("/coverage",Map.of("fund_codes",codes));
        Set<String> actual=new HashSet<>(); result.path("items").forEach(r->actual.add(r.path("fund_code").asText()));
        if(!actual.equals(new HashSet<>(codes)) || result.path("items").size()!=actual.size()) throw new IllegalStateException("SCOPE_MISMATCH");
        return result;
    }
}

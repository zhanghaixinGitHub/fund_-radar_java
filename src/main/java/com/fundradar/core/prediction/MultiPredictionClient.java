package com.fundradar.core.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.integration.ai.AiServiceProperties;
import com.fundradar.core.integration.ai.PublicDataFailure;
import com.fundradar.core.common.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** 公共预测只传基金和任务编号；个人持仓和用户身份始终留在Java。 */
@Service
public class MultiPredictionClient {
    private static final Logger LOG=LoggerFactory.getLogger(MultiPredictionClient.class);
    private final RestClient client; private final AiServiceProperties config; private final ObjectMapper json;
    public MultiPredictionClient(RestClient.Builder builder,AiServiceProperties config,ObjectMapper json) {
        this.config=config;this.json=json;
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getConnectTimeout()); factory.setReadTimeout(config.getMultiPredictionReadTimeout());
        client=builder.baseUrl(config.getBaseUrl()+"/internal/v1/multi-predictions").requestFactory(factory).build();
    }
    public JsonNode get(String path) { return request(path,null); }
    public JsonNode post(String path,Object body) { return request(path,body); }
    private JsonNode request(String path,Object body) {
        boolean idempotent=body==null || path.endsWith("/outcomes") ||
                ("/batches".equals(path) && body instanceof java.util.Map<?,?> map && map.get("requestKey")!=null);
        for(int attempt=0;;attempt++) {
        try {
            RestClient.RequestHeadersSpec<?> request=body==null ? client.get().uri(path) : client.post().uri(path).body(body);
            var value=request.header("X-Service-Token",config.getToken()).header("X-Trace-Id",TraceContext.getTraceId())
                    .retrieve().body(String.class);
            if(value==null||value.length()>32_000_000) throw new IllegalStateException("INVALID_PUBLIC_RESPONSE");
            // Boot 4 Web使用Jackson 3，证据层沿用Jackson 2；按已有一日客户端先读文本再解析。
            return json.readTree(value);
        } catch(Exception error) {
            var detail=PublicDataFailure.classify(error,"PREDICTION_SERVICE",TraceContext.getTraceId());
            LOG.warn("MultiPredictionClient.request   >>> traceId={}, code={}, errorType={}",
                    detail.traceId(),detail.code(),error.getClass().getSimpleName(),error);
            // 只重试读操作或带同一幂等键的生成；研究创建和身份/参数/包错误不盲目重发。
            boolean transientFailure=error instanceof org.springframework.web.client.ResourceAccessException ||
                    (error instanceof org.springframework.web.client.HttpStatusCodeException http && http.getStatusCode().is5xxServerError());
            if(idempotent && transientFailure && detail.retryable() && attempt<2) {
                try {Thread.sleep(attempt==0?100:300);} catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt();throw new PredictionServiceFailure(detail,interrupted);
                }
                continue;
            }
            throw new PredictionServiceFailure(detail,error);
        }
        }
    }
    public static class PredictionServiceFailure extends RuntimeException {
        private final PublicDataFailure.Error detail;
        public PredictionServiceFailure(PublicDataFailure.Error detail,Throwable cause) { super(detail.summary(),cause); this.detail=detail; }
        public PublicDataFailure.Error detail() { return detail; }
    }
}

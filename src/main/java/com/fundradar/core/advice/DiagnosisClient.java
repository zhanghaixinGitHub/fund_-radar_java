package com.fundradar.core.advice;

import com.fundradar.core.common.trace.TraceContext;
import com.fundradar.core.integration.ai.AiServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.time.LocalDate;
import java.util.HashSet;
import static com.fundradar.core.advice.DiagnosisTypes.*;

/** 只向Python发送基金代码与日期；异常不透传地址、令牌、SQL或内部响应，由调用方降级为数据不足。 */
@Service
public class DiagnosisClient {
    private static final Logger LOGGER=LoggerFactory.getLogger(DiagnosisClient.class);
    private final RestClient client;
    private final AiServiceProperties properties;
    public DiagnosisClient(RestClient.Builder builder,AiServiceProperties properties) {
        this.properties=properties;
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout()); factory.setReadTimeout(properties.getReadTimeout());
        client=builder.baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
    }
    /** 校验失败或来源不可用时抛出异常；服务层据此记INSUFFICIENT，不补造事实。 */
    public Facts read(String fundCode,LocalDate asOfDate) {
        long started=System.nanoTime();
        try {
            var result=client.get().uri(b->b.path("/internal/v1/portfolio-advice/{code}/diagnosis-facts")
                    .queryParam("asOfDate",asOfDate).build(fundCode))
                    .header("X-Service-Token",properties.getToken()).header("X-Trace-Id",TraceContext.getTraceId())
                    .retrieve().body(Facts.class);
            if(!valid(result,fundCode)) throw new IllegalArgumentException("Invalid diagnosis facts");
            LOGGER.info("DiagnosisClient.read   >>>   traceId={}, fundCode={}, overall={}, elapsedMs={}",
                    TraceContext.getTraceId(),fundCode,result.overall(),(System.nanoTime()-started)/1_000_000);
            return result;
        } catch(RuntimeException error) {
            LOGGER.warn("DiagnosisClient.read   >>>   unavailable, traceId={}, fundCode={}, errorType={}, elapsedMs={}",
                    TraceContext.getTraceId(),fundCode,error.getClass().getSimpleName(),(System.nanoTime()-started)/1_000_000);
            LOGGER.debug("DiagnosisClient.read   >>>   failure frames={}",java.util.Arrays.toString(error.getStackTrace()));
            throw error;
        }
    }
    private static boolean valid(Facts result,String fundCode) {
        if(result==null || !fundCode.equals(result.fundCode()) || result.asOfDate()==null
                || !"HOLDING_DIAGNOSIS_FACTS_V1".equals(result.basis()) || !VERDICTS.contains(result.overall())
                || result.items()==null || result.items().size()!=ITEM_KEYS.size()) return false;
        var keys=new HashSet<String>();
        for(var item:result.items()) {
            if(item==null || !ITEM_KEYS.contains(item.item()) || !keys.add(item.item())
                    || !VERDICTS.contains(item.verdict()) || item.evidence()==null || item.evidence().length()>2000
                    || item.source()==null || item.source().length()>100) return false;
        }
        return keys.size()==ITEM_KEYS.size();
    }
}

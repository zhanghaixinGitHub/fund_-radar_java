package com.fundradar.core.advice;

import com.fundradar.core.common.trace.TraceContext;
import com.fundradar.core.integration.ai.AiServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;
import java.util.HashSet;
import static com.fundradar.core.advice.RuleTypes.*;

/** 只向Python发送基金代码；异常不透传地址、令牌、SQL或内部响应，由调用方保留旧草案降级。 */
@Service
public class DraftStatsClient {
    private static final Logger LOGGER=LoggerFactory.getLogger(DraftStatsClient.class);
    private final RestClient client;
    private final AiServiceProperties properties;
    public DraftStatsClient(RestClient.Builder builder,AiServiceProperties properties) {
        this.properties=properties;
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout()); factory.setReadTimeout(properties.getReadTimeout());
        client=builder.baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
    }
    /** 契约校验失败或来源不可用时抛出异常；调度据此保留旧草案与已确认规则不变。 */
    public DraftStats read(String fundCode) {
        long started=System.nanoTime();
        try {
            var result=client.get().uri("/internal/v1/portfolio-advice/{code}/draft-stats",fundCode)
                    .header("X-Service-Token",properties.getToken())
                    .header("X-Trace-Id",TraceContext.getTraceId())
                    .retrieve().body(DraftStats.class);
            if(!valid(result,fundCode)) throw new IllegalArgumentException("Invalid draft stats");
            LOGGER.info("DraftStatsClient.read   >>>   traceId={}, fundCode={}, status={}, elapsedMs={}",
                    TraceContext.getTraceId(),fundCode,result.status(),(System.nanoTime()-started)/1_000_000);
            return result;
        } catch(RuntimeException error) {
            LOGGER.warn("DraftStatsClient.read   >>>   unavailable, traceId={}, fundCode={}, errorType={}, elapsedMs={}",
                    TraceContext.getTraceId(),fundCode,error.getClass().getSimpleName(),(System.nanoTime()-started)/1_000_000);
            LOGGER.debug("DraftStatsClient.read   >>>   failure frames={}",java.util.Arrays.toString(error.getStackTrace()));
            throw error;
        }
    }
    private static boolean valid(DraftStats result,String fundCode) {
        if(result==null || !fundCode.equals(result.fundCode()) || !"HOLDING_RULE_DRAFT_STATS_V1".equals(result.basis())
                || !DRAFT_STATUS.contains(result.status())) return false;
        if(!"AVAILABLE".equals(result.status()))
            return result.reason()!=null && result.reason().length()<=600 && (result.tiers()==null || result.tiers().isEmpty());
        if(result.statsCutoffDate()==null || result.stats()==null || result.tiers()==null
                || result.tiers().size()!=TIERS.size() || result.assumption()==null) return false;
        var keys=new HashSet<String>();
        for(var tier:result.tiers()) {
            if(tier==null || !TIERS.contains(tier.tier()) || !keys.add(tier.tier())
                    || tier.reduceDrawdownPct()==null || tier.takeProfitPct()==null
                    || tier.reduceTrigger()==null || tier.takeProfitTrigger()==null) return false;
            if(tier.reduceDrawdownPct().compareTo(BigDecimal.ZERO)>0
                    || tier.takeProfitPct().compareTo(BigDecimal.ZERO)<0) return false;
        }
        return keys.size()==TIERS.size();
    }
}

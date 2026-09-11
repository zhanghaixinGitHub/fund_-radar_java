package com.fundradar.core.advice;

import com.fundradar.core.common.trace.TraceContext;
import com.fundradar.core.integration.ai.AiServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.time.Clock;
import java.util.List;
import static com.fundradar.core.advice.AdviceTypes.*;

/** 只向Python发送基金和日期；异常不透传地址、令牌、SQL或内部响应。 */
@Service
public class AdviceOutcomeClient {
    private static final Logger LOGGER=LoggerFactory.getLogger(AdviceOutcomeClient.class);
    private final RestClient client;
    private final AiServiceProperties properties;
    private final Clock clock;
    public AdviceOutcomeClient(RestClient.Builder builder,AiServiceProperties properties,Clock clock) {
        this.properties=properties; this.clock=clock;
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout()); factory.setReadTimeout(properties.getReadTimeout());
        client=builder.baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
    }
    public Outcome read(Pending row) {
        try {
            var result=client.get().uri(b->b.path("/internal/v1/portfolio-advice/{code}/outcome")
                    .queryParam("startDate",row.start()).queryParam("endDate",row.end()).build(row.fundCode()))
                    .header("X-Service-Token",properties.getToken()).header("X-Trace-Id",TraceContext.getTraceId())
                    .retrieve().body(Outcome.class);
            if(result==null || !row.fundCode().equals(result.fundCode()) || !row.start().equals(result.startDate())
                    || !row.end().equals(result.endDate()) || result.checkedAt()==null || result.status()==null
                    || !List.of("WAITING","DATA_INSUFFICIENT","ASSESSED").contains(result.status())
                    || !"CASH_REINVESTMENT_20D_V1".equals(result.basis()) || !"TUSHARE_PRO_FUND".equals(result.source())
                    || result.message()==null || result.message().length()>600
                    || ("ASSESSED".equals(result.status()) && (result.totalReturn()==null || result.evidence()==null
                        || result.evidenceHash()==null || !result.evidenceHash().matches("[0-9a-f]{64}")))
                    || (!"ASSESSED".equals(result.status()) && result.totalReturn()!=null))
                throw new IllegalArgumentException("Invalid advice outcome");
            return result;
        } catch(RuntimeException error) {
            LOGGER.warn("AdviceOutcomeClient.read   >>>   traceId={}, fundCode={}, errorType={}",TraceContext.getTraceId(),row.fundCode(),error.getClass().getSimpleName());
            LOGGER.debug("AdviceOutcomeClient.read   >>>   failure frames={}",java.util.Arrays.toString(error.getStackTrace()));
            return new Outcome(row.fundCode(),row.start(),row.end(),"DATA_INSUFFICIENT",clock.instant(),null,
                    "后续表现暂时无法核验，原报告与已核验记录保留。","CASH_REINVESTMENT_20D_V1","TUSHARE_PRO_FUND",null,null);
        }
    }
}

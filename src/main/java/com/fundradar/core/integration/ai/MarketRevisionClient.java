package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.common.trace.TraceContext;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.time.LocalDate;
import java.util.*;

/** 批量读取公共行情内容版本；不传用户、金额、份额，不在查询时启动采集。 */
@Service
public class MarketRevisionClient {
    public record Query(String key,String fundCode,LocalDate startDate,LocalDate endDate,String kind) {}
    public record Revision(String value,boolean ready) {}
    public record Snapshot(String calendarRevision,Map<String,Revision> items) {}
    private final RestClient client;
    private final AiServiceProperties properties;
    private final ObjectMapper json;

    public MarketRevisionClient(RestClient.Builder builder,AiServiceProperties properties,ObjectMapper json) {
        this.properties=properties; this.json=json;
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout()); factory.setReadTimeout(properties.getReadTimeout());
        client=builder.baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
    }

    /** 每批最多 50 个区间；缺项、重复项或版本格式错误整批失败，调用方不得推进已处理水位。 */
    public Snapshot read(List<Query> queries) {
        if(queries.isEmpty() || queries.size()>50 || queries.stream().map(Query::key).distinct().count()!=queries.size())
            throw new IllegalArgumentException("INVALID_REVISION_SCOPE");
        try {
            var body=queries.stream().map(q->Map.of("key",q.key(),"fund_code",q.fundCode(),
                    "start_date",q.startDate().toString(),"end_date",q.endDate().toString(),"kind",q.kind())).toList();
            String raw=client.post().uri("/internal/v1/market-revisions")
                    .header("X-Service-Token",properties.getToken()).header("X-Trace-Id",TraceContext.getTraceId())
                    .body(Map.of("items",body)).retrieve().body(String.class);
            if(raw==null || raw.length()>100_000) throw new IllegalStateException("INVALID_REVISION_RESPONSE");
            var response=json.readTree(raw); String calendar=response.path("calendar_revision").asText();
            if(!calendar.matches("[a-f0-9]{64}") || !response.path("items").isArray())
                throw new IllegalStateException("INVALID_REVISION_RESPONSE");
            Map<String,Revision> items=new HashMap<>();
            for(var row:response.path("items")) {
                String key=row.path("key").asText(),value=row.path("revision").asText();
                if(!value.matches("[a-f0-9]{64}") || !row.path("ready").isBoolean()
                        || items.put(key,new Revision(value,row.path("ready").asBoolean()))!=null)
                    throw new IllegalStateException("INVALID_REVISION_RESPONSE");
            }
            if(!items.keySet().equals(new HashSet<>(queries.stream().map(Query::key).toList())))
                throw new IllegalStateException("REVISION_SCOPE_MISMATCH");
            return new Snapshot(calendar,Map.copyOf(items));
        } catch(Exception error) {
            throw new IllegalStateException("行情变化检查暂不可用，保留上次处理状态。",error);
        }
    }
}

package com.fundradar.core.simulation;

import com.fundradar.core.integration.ai.AiServiceProperties;
import com.fundradar.core.common.trace.TraceContext;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static com.fundradar.core.simulation.SimulationTypes.*;

/** 独立的公共结算资料接口，不复用关注详情授权，也不向 Python 发送个人数据。 */
@Service
public class SimulationMarketClient {
    private static final Logger LOGGER=LoggerFactory.getLogger(SimulationMarketClient.class);
    private final RestClient client;
    private final AiServiceProperties properties;
    public SimulationMarketClient(RestClient.Builder builder, AiServiceProperties properties) {
        this.properties = properties;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout());
        factory.setReadTimeout(properties.getReadTimeout());
        this.client = builder.baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
    }
    public CalendarData calendar() {
        try {
            var result = get("/internal/v1/simulation/calendar").retrieve().body(CalendarData.class);
            if (result == null) throw unavailable();
            return result;
        } catch (RuntimeException error) { LOGGER.warn("SimulationMarketClient.calendar   >>> public calendar unavailable",error); throw unavailable(); }
    }
    public Market market(String code, LocalDate start, LocalDate end) {
        try {
            var result = client.get().uri(builder -> builder.path("/internal/v1/simulation/funds/{code}")
                    .queryParam("startDate",start).queryParam("endDate",end).build(code))
                    .header("X-Service-Token",properties.getToken()).header("X-Trace-Id",TraceContext.getTraceId())
                    .retrieve().body(Market.class);
            if (result == null || !code.equals(result.fundCode())) throw unavailable();
            return result;
        } catch (HttpClientErrorException.NotFound error) { throw new SimulationException("SIM_NOT_FOUND","未找到已登记的基金资料。"); }
        catch (RuntimeException error) { LOGGER.warn("SimulationMarketClient.market   >>> public market unavailable, fundCode={}",code,error); throw unavailable(); }
    }
    public void refresh(List<String> codes) {
        if (codes.isEmpty()) return;
        try {
            client.post().uri("/internal/v1/simulation/refresh")
                    .header("X-Service-Token",properties.getToken()).header("X-Trace-Id",TraceContext.getTraceId())
                    .body(Map.of("fundCodes",codes)).retrieve().toBodilessEntity();
        } catch (RuntimeException error) { LOGGER.warn("SimulationMarketClient.refresh   >>> public refresh request failed",error); throw unavailable(); }
    }
    private RestClient.RequestHeadersSpec<?> get(String path) {
        return client.get().uri(path).header("X-Service-Token",properties.getToken())
                .header("X-Trace-Id",TraceContext.getTraceId());
    }
    private SimulationException unavailable() {
        return new SimulationException("SIM_MARKET_UNAVAILABLE","结算行情暂时不可用，请稍后重试；已有订单仍保留。");
    }
}

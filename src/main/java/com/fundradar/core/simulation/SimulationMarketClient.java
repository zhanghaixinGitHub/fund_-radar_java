package com.fundradar.core.simulation;

import com.fundradar.core.integration.ai.AiServiceProperties;
import com.fundradar.core.common.trace.TraceContext;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
        } catch (RuntimeException error) {
            var failure=com.fundradar.core.integration.ai.PublicDataFailure.classify(error,"CALENDAR",TraceContext.getTraceId());
            LOGGER.warn("SimulationMarketClient.calendar   >>> traceId={}, code={}, calendar request failed",
                    failure.traceId(),failure.code(),error);
            throw new SimulationException(failure.code(),failure.summary()+" 请求号："+failure.traceId());
        }
    }
    /**
     * 读取模拟结算资料并记录跨服务耗时；日期是所需净值区间，耗时单位为毫秒。
     * 定时任务没有 HTTP 请求上下文时补充请求号，并在发送、日志和异常中复用同一个值。
     * 完成后恢复原 MDC，避免调度线程复用时把请求号串到其他任务。
     */
    public Market market(String code, LocalDate start, LocalDate end) {
        String previousTrace = MDC.get(TraceContext.TRACE_ID_KEY);
        String traceId = previousTrace == null || previousTrace.isBlank() ? UUID.randomUUID().toString() : previousTrace;
        long started = System.nanoTime();
        MDC.put(TraceContext.TRACE_ID_KEY, traceId);
        LOGGER.debug("SimulationMarketClient.market   >>> phase=start traceId={} fundCode={} startDate={} endDate={} readTimeoutMs={}",
                traceId, code, start, end, properties.getReadTimeout().toMillis());
        try {
            var result = client.get().uri(builder -> builder.path("/internal/v1/simulation/funds/{code}")
                    .queryParam("startDate",start).queryParam("endDate",end).build(code))
                    .header("X-Service-Token",properties.getToken()).header("X-Trace-Id",traceId)
                    .retrieve().body(Market.class);
            if (result == null || !code.equals(result.fundCode())) throw unavailable();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            // 正常逐基金查询只在 DEBUG 记录；慢调用保留 WARN，不输出行情或个人账目。
            if (elapsedMs >= 1000) {
                LOGGER.warn("SimulationMarketClient.market   >>> phase=end traceId={} fundCode={} outcome=slow_success elapsedMs={}",
                        traceId, code, elapsedMs);
            } else {
                LOGGER.debug("SimulationMarketClient.market   >>> phase=end traceId={} fundCode={} outcome=success elapsedMs={}",
                        traceId, code, elapsedMs);
            }
            return result;
        } catch (HttpClientErrorException.NotFound error) {
            logMarketFailure(traceId, code, start, end, started, error);
            throw new SimulationException("SIM_NOT_FOUND","未找到已登记的基金资料。");
        } catch (RuntimeException error) {
            logMarketFailure(traceId, code, start, end, started, error);
            throw unavailable();
        } finally {
            if (previousTrace == null) MDC.remove(TraceContext.TRACE_ID_KEY);
            else MDC.put(TraceContext.TRACE_ID_KEY, previousTrace);
        }
    }

    /** 保留异常类型、根因和调用堆栈，不把 HTTP 错误响应原文、URL 或凭据复制到新增日志。 */
    private void logMarketFailure(String traceId, String code, LocalDate start, LocalDate end,
                                  long started, RuntimeException error) {
        LOGGER.warn("SimulationMarketClient.market   >>> phase=end traceId={} fundCode={} startDate={} endDate={} "
                        + "outcome=failed elapsedMs={} readTimeoutMs={} errorType={}",
                traceId, code, start, end, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                properties.getReadTimeout().toMillis(), error.getClass().getSimpleName(), safeStack(error, 0));
    }

    /** 有界复制异常链，只保留类型与堆栈位置，避免异常消息携带服务端响应或敏感参数。 */
    private RuntimeException safeStack(Throwable error, int depth) {
        var safe = new RuntimeException(error.getClass().getName());
        safe.setStackTrace(error.getStackTrace());
        if (error.getCause() != null && error.getCause() != error && depth < 8) {
            safe.initCause(safeStack(error.getCause(), depth + 1));
        }
        return safe;
    }
    public void refresh(List<String> codes) {
        if (codes.isEmpty()) return;
        try {
            client.post().uri("/internal/v1/simulation/refresh")
                    .header("X-Service-Token",properties.getToken()).header("X-Trace-Id",TraceContext.getTraceId())
                    .body(Map.of("fundCodes",codes)).retrieve().toBodilessEntity();
        } catch (RuntimeException error) { LOGGER.warn("SimulationMarketClient.refresh   >>> public refresh request failed",error); throw unavailable(); }
    }
    /** 抓取单基金费率档案（天天基金 f10 经 Python 解析）；费率结构无法自动解析时要求人工维护。 */
    public FundFee fetchFees(String code) {
        try {
            var result = client.get().uri("/internal/v1/simulation/fees/{code}",code)
                    .header("X-Service-Token",properties.getToken()).header("X-Trace-Id",TraceContext.getTraceId())
                    .retrieve().body(FundFee.class);
            if (result == null || !code.equals(result.fundCode())) throw unavailable();
            return result;
        } catch (HttpClientErrorException.UnprocessableEntity error) {
            throw new SimulationException("SIM_FEE_MANUAL_REQUIRED","该基金费率结构无法自动解析，请人工维护："
                    + detail(error.getResponseBodyAsString()));
        } catch (HttpClientErrorException.NotFound error) { throw new SimulationException("SIM_NOT_FOUND","未找到已登记的基金资料。"); }
        catch (RuntimeException error) { LOGGER.warn("SimulationMarketClient.fetchFees   >>> fee profile unavailable, fundCode={}",code,error); throw unavailable(); }
    }
    /** 从 FastAPI 错误响应中提取 detail 文案（{"detail":"..."}），解析失败时退回通用提示。 */
    private String detail(String body) {
        int start=body.indexOf("\"detail\":\"");
        if(start<0) return "费率结构异常";
        int end=body.indexOf('"',start+10);
        return end>start ? body.substring(start+10,end) : "费率结构异常";
    }
    private RestClient.RequestHeadersSpec<?> get(String path) {
        return client.get().uri(path).header("X-Service-Token",properties.getToken())
                .header("X-Trace-Id",TraceContext.getTraceId());
    }
    private SimulationException unavailable() {
        return new SimulationException("SIM_MARKET_UNAVAILABLE","结算行情暂时不可用，请稍后重试；已有订单仍保留。");
    }
}

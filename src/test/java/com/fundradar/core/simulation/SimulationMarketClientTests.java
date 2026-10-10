package com.fundradar.core.simulation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.fundradar.core.common.trace.TraceContext;
import com.fundradar.core.integration.ai.AiServiceProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 只使用本地临时 HTTP 替身，验证真实读取超时、请求号一致性和敏感消息不进入日志。 */
class SimulationMarketClientTests {
    private final Logger logger = (Logger) LoggerFactory.getLogger(SimulationMarketClient.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level previousLevel;
    private HttpServer server;

    @BeforeEach void prepare() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);
        MDC.remove(TraceContext.TRACE_ID_KEY);
    }

    @AfterEach void cleanup() {
        server.stop(0);
        logger.detachAppender(logs);
        logger.setLevel(previousLevel);
        logs.stop();
        MDC.remove(TraceContext.TRACE_ID_KEY);
    }

    private SimulationMarketClient client(Duration timeout) {
        var properties = new AiServiceProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setToken("test-secret-token");
        properties.setReadTimeout(timeout);
        return new SimulationMarketClient(RestClient.builder(), properties);
    }

    private void request(SimulationMarketClient client) {
        client.market("007045", LocalDate.of(2026, 9, 4), LocalDate.of(2026, 10, 9));
    }

    @Test void scheduledCallsGenerateDistinctTracesAndRestoreCallerContext() {
        var traces = new ArrayList<String>();
        server.createContext("/", exchange -> {
            traces.add(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            var bytes = "{\"fundCode\":\"007045\",\"supported\":true,\"navs\":[],\"dividends\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var client = client(Duration.ofSeconds(3));
        request(client);
        assertNull(MDC.get(TraceContext.TRACE_ID_KEY));
        request(client);
        assertNotEquals(traces.get(0), traces.get(1));
        MDC.put(TraceContext.TRACE_ID_KEY, "caller-trace");
        request(client);
        assertEquals("caller-trace", traces.get(2));
        assertEquals("caller-trace", MDC.get(TraceContext.TRACE_ID_KEY));
        for (String trace : traces) {
            assertFalse(trace.isBlank());
            assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("traceId=" + trace)));
        }
        assertTrue(logs.list.stream().noneMatch(e -> e.getFormattedMessage().contains("test-secret-token")));
    }

    @Test void timeoutLogsSharedTraceDurationAndRootCauseWithoutChangingBusinessError() {
        var release = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            try { release.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        MDC.put(TraceContext.TRACE_ID_KEY, "timeout-shared-trace");
        try {
            var failure = assertThrows(SimulationException.class, () -> request(client(Duration.ofMillis(80))));
            assertEquals("SIM_MARKET_UNAVAILABLE", failure.code());
            var event = logs.list.stream().filter(e -> e.getLevel() == Level.WARN).findFirst().orElseThrow();
            assertTrue(event.getFormattedMessage().contains("traceId=timeout-shared-trace"));
            assertTrue(event.getFormattedMessage().contains("elapsedMs="));
            assertTrue(event.getFormattedMessage().contains("readTimeoutMs=80"));
            assertTrue(ThrowableProxyUtil.asString(event.getThrowableProxy()).contains("SocketTimeoutException"));
            assertEquals("timeout-shared-trace", MDC.get(TraceContext.TRACE_ID_KEY));
        } finally { release.countDown(); }
    }

    @Test void notFoundKeepsContractAndDoesNotLogResponseBody() {
        server.createContext("/", exchange -> {
            var bytes = "private-upstream-response".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var failure = assertThrows(SimulationException.class, () -> request(client(Duration.ofSeconds(3))));
        assertEquals("SIM_NOT_FOUND", failure.code());
        var event = logs.list.stream().filter(e -> e.getLevel() == Level.WARN).findFirst().orElseThrow();
        assertFalse(ThrowableProxyUtil.asString(event.getThrowableProxy()).contains("private-upstream-response"));
        assertNull(MDC.get(TraceContext.TRACE_ID_KEY));
    }
}

package com.fundradar.core.integration.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 本机 HTTP 替身验证严格比较的新契约、日期及服务令牌，不访问真实外部服务。 */
class FundPeerComparisonClientTests {
    @Test
    void readsVerifiedGroupAndCommonWindowWithInternalToken() throws Exception {
        var token = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/funds/002112/same-type-comparison", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Service-Token"));
            var bytes = """
                    {"fund_code":"002112","fund_type":"MIXED","scope":"VERIFIED_CURRENT_MARKET_PEER_GROUP",
                     "status":"NO_PEERS","as_of_date":"2026-10-23","target_rank":null,"comparable_count":0,
                     "items":[],"comparison_group":{"name":"境内灵活配置混合基金","description":"股票0%-95%。",
                     "basis":"VERIFIED_DISCLOSURE"},"comparison_start_date":"2026-09-23","sample_unit":"PRODUCT",
                     "comparable_product_count":0,"reason":"暂无其他可比基金。"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var properties = new AiServiceProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setToken("isolated-peer-test-token");
            var client = new AiFundClient(RestClient.builder(), properties);
            var response = client.getFundSameTypeComparison("002112");
            assertEquals("isolated-peer-test-token", token.get());
            assertEquals("境内灵活配置混合基金", response.comparisonGroup().name());
            assertEquals(LocalDate.of(2026, 9, 23), response.comparisonStartDate());
            assertEquals("PRODUCT", response.sampleUnit());
            assertEquals("暂无其他可比基金。", response.reason());
        } finally {
            server.stop(0);
        }
    }
}

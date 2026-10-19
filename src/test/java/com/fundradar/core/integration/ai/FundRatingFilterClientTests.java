package com.fundradar.core.integration.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 使用本机临时 HTTP 替身验证筛选参数、令牌及筛选评级反序列化，不访问真实外部服务。 */
class FundRatingFilterClientTests {
    @Test void forwardsFilterAndReadsVerifiedSummaries() throws Exception {
        var requests = new ArrayList<Map<String, String>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/funds", exchange -> {
            requests.add(Map.of("query", exchange.getRequestURI().getQuery(),
                    "token", exchange.getRequestHeaders().getFirst("X-Service-Token")));
            var bytes = """
                    {"items":[{"fund_code":"002112","fund_name":"测试基金","fund_type":"MIXED","status":"ACTIVE"}],
                     "page":2,"page_size":10,"total_count":11,"total_pages":2,
                     "ratings":{"002112":{"fundCode":"002112","status":"RATED","grade":"GOOD","gradeLabel":"良好"}}}
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
            properties.setToken("offline-test-token");
            var client = new AiFundClient(RestClient.builder(), properties);
            var result = client.listFunds("华夏", "MIXED", 10, null, 2, "GOOD");
            assertEquals("GOOD", result.ratings().get("002112").grade());
            assertEquals(11, result.totalCount());
            assertTrue(requests.get(0).get("query").contains("rating=GOOD"));
            assertTrue(requests.get(0).get("query").contains("keyword=华夏"));
            assertEquals("offline-test-token", requests.get(0).get("token"));
            assertFalse(requests.get(0).get("query").contains("token"));
        } finally {
            server.stop(0);
        }
    }
}

package com.fundradar.core.integration.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 用延迟的本机HTTP替身验证评级专用等待；其他资料查询仍受原短超时保护。 */
class FundRatingReadTimeoutTests {
    @Test
    void ratingReadsHaveTheirOwnBoundedTimeout() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/funds", exchange -> {
            try {
                Thread.sleep(200);
                String query = exchange.getRequestURI().getQuery();
                String body = query != null && query.contains("rating=SCORED")
                        ? "{\"items\":[],\"page\":1,\"page_size\":10,\"total_count\":1,\"total_pages\":1,"
                            + "\"ratings\":{\"013275\":{\"fundCode\":\"013275\",\"status\":\"SCORED\",\"score\":\"73.32\"}}}"
                        : exchange.getRequestURI().getPath().endsWith("ratings")
                        ? "{\"items\":[{\"fundCode\":\"002112\",\"status\":\"PARTIAL\"}]}"
                        : "{\"fundCode\":\"002112\",\"status\":\"PARTIAL\"}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var properties = new AiServiceProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setToken("isolated-test-token");
            properties.setReadTimeout(Duration.ofMillis(50));
            var client = new AiFundClient(RestClient.builder(), properties);
            assertEquals("PARTIAL", client.getFundRatings("002112").items().get(0).status());
            assertEquals("PARTIAL", client.getFundRating("002112", null).status());
            var filtered = client.listFunds(null, null, 10, null, 1, "SCORED");
            assertEquals("SCORED", filtered.ratings().get("013275").status());
            assertEquals("73.32", filtered.ratings().get("013275").score());
            assertThrows(AiServiceUnavailableException.class,
                    () -> client.listFunds(null, null, 10, null, 1));
            assertThrows(AiServiceUnavailableException.class, () -> client.getFundMaterials("002112", null, null));
        } finally {
            server.stop(0);
        }
    }
}

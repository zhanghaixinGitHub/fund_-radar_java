package com.fundradar.core.fund.controller;

import com.fundradar.core.integration.ai.AiFundManagerDirectoryClient;
import com.fundradar.core.integration.ai.AiServiceProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实HTTP转换器核对Python驼峰JSON、日期和可空统计，不依赖真实供应商。 */
class FundManagerDirectoryClientTests {
    @Test void decodesActualPublicPageContract() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/fund-managers", exchange -> {
            String json = """
                {"items":[{"managerId":"48310c82-eaa6-5d87-b1ce-0f78dfee2395","managerName":"陆阳",
                "snapshotId":"33333333-3333-4333-8333-333333333333","companies":["德邦基金"],"education":"硕士",
                "shareCount":8,"productCount":null,"historyCount":0,"anchorFundCode":"002112","anchorFundName":"测试基金",
                "checkedAt":"2026-10-26T08:51:48.163298Z"}],"page":1,"pageSize":20,"totalCount":1,"totalPages":1,
                "snapshotId":"44444444-4444-4444-8444-444444444444","notice":"已收录资料"}
                """;
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var properties = new AiServiceProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setToken("test-only-token");
            var client = new AiFundManagerDirectoryClient(RestClient.builder(), properties);
            var page = client.list("陆阳", "", "", "all", 1, 20, null);
            assertEquals("陆阳", page.items().get(0).managerName());
            assertEquals(8, page.items().get(0).shareCount());
            assertNull(page.items().get(0).productCount());
        } finally { server.stop(0); }
    }
}

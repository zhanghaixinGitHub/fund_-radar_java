package com.fundradar.core.integration.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 本机临时 HTTP 服务验证真实序列化、内部认证和响应完整性，无真实服务调用。 */
class MarketRevisionClientTests {
    @Test void batchContractAndMalformedResponses() throws Exception {
        var json=new ObjectMapper(); var requests=new ArrayList<Map<String,String>>();
        var response=new String[]{""}; var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/v1/market-revisions",exchange->{
            requests.add(Map.of("method",exchange.getRequestMethod(),"token",exchange.getRequestHeaders().getFirst("X-Service-Token"),
                    "body",new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            var bytes=response[0].getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var properties=new AiServiceProperties();
            properties.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort()); properties.setToken("offline-test-token");
            var client=new MarketRevisionClient(RestClient.builder(),properties,json);
            var query=List.of(new MarketRevisionClient.Query("002112","002112",LocalDate.of(2026,10,8),LocalDate.of(2026,10,9),"LABEL"));
            var row=Map.of("key","002112","revision","b".repeat(64),"ready",true);
            response[0]=json.writeValueAsString(Map.of("calendar_revision","a".repeat(64),"items",List.of(row)));
            assertTrue(client.read(query).items().get("002112").ready());
            assertEquals("POST",requests.get(0).get("method"));
            assertEquals("offline-test-token",requests.get(0).get("token"));
            assertEquals("2026-10-08",json.readTree(requests.get(0).get("body")).path("items").get(0).path("start_date").asText());
            assertFalse(requests.get(0).get("body").contains("user"));
            for(var items:List.of(List.of(),List.of(row,row),List.of(Map.of("key","other","revision","b".repeat(64),"ready",true)),
                    List.of(Map.of("key","002112","revision","invalid","ready",true)))) {
                response[0]=json.writeValueAsString(Map.of("calendar_revision","a".repeat(64),"items",items));
                assertThrows(IllegalStateException.class,()->client.read(query));
            }
        } finally { server.stop(0); }
    }
}

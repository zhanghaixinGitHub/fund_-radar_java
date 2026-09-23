package com.fundradar.core.prediction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.integration.ai.AiServiceProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MultiPredictionClientTests {
    @Test void safeBatchRetriesSameRequestAndJacksonTreeIsReadable() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var calls=new AtomicInteger();var bodies=new ArrayList<String>();
        server.createContext("/internal/v1/multi-predictions/batches", exchange->{
            bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            int status=calls.incrementAndGet()==1?503:200;
            var bytes="{\"taskId\":\"same-task\",\"plannedItems\":3}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        try {
            var config=new AiServiceProperties();config.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
            var client=new MultiPredictionClient(RestClient.builder(),config,new ObjectMapper());
            var result=client.post("/batches",Map.of("fundCodes",List.of("006730"),"requestKey",UUID.randomUUID()));
            assertEquals(2,calls.get());assertEquals(bodies.get(0),bodies.get(1));
            assertEquals(3,result.path("plannedItems").asInt());
            assertInstanceOf(Map.class,PredictionWebData.of(result));
        } finally {server.stop(0);}
    }
    @Test void authAndNonIdempotentResearchAreNeverRetried() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        server.createContext("/",exchange->{calls.incrementAndGet();exchange.sendResponseHeaders(403,-1);exchange.close();});
        server.start();
        try {
            var config=new AiServiceProperties();config.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
            var client=new MultiPredictionClient(RestClient.builder(),config,new ObjectMapper());
            assertThrows(MultiPredictionClient.PredictionServiceFailure.class,()->client.get("/models"));
            assertEquals(1,calls.get());
            assertThrows(MultiPredictionClient.PredictionServiceFailure.class,()->client.post("/research",Map.of()));
            assertEquals(2,calls.get());
        } finally {server.stop(0);}
    }
}

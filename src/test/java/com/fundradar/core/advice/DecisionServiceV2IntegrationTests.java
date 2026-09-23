package com.fundradar.core.advice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.auth.*;
import com.fundradar.core.prediction.MultiPredictionClient;
import com.fundradar.core.simulation.SimulationRepository;
import com.fundradar.core.simulation.SimulationTypes;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实本机PostgreSQL事务留档验收，公共模型/仓位为明确测试替身；事务回滚不修改真实持仓。 */
@SpringBootTest
@Transactional
class DecisionServiceV2IntegrationTests {
    @Autowired DecisionServiceV2 service;
    @Autowired JdbcClient db;
    @Autowired ObjectMapper json;
    @MockitoBean MultiPredictionClient client;
    @MockitoBean SimulationRepository positions;
    @MockitoBean DiagnosisClient diagnoses;
    @MockitoBean RuleRepository rules;
    @MockitoBean Clock clock;
    UUID owner;

    @BeforeEach void setup() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-09-22T12:00:00Z"));
        owner=UUID.randomUUID();
        db.sql("INSERT INTO user_account(user_id,mobile,display_name,password_hash,role,status) VALUES(:id,:mobile,'综合建议事务验收','fixture','FUND_USER','ACTIVE')")
                .param("id",owner).param("mobile","139"+String.format("%08d",ThreadLocalRandom.current().nextInt(100000000))).update();
        db.sql("INSERT INTO watchlist_item(watchlist_item_id,user_id,fund_code,fund_type) VALUES(:id,:u,'123456','STOCK')")
                .param("id",UUID.randomUUID()).param("u",owner).update();
        CurrentUserContext.set(new AuthenticatedUser(owner,"fixture","fixture",AccountRole.FUND_USER,
                Set.of(PermissionCode.PORTFOLIO_SELF_READ,PermissionCode.SIM_PORTFOLIO_SELF_WRITE)));
        when(client.get("/funds/123456")).thenReturn(json.readTree("""
          {"predictions":[{"predictionId":"fixture-original","horizonId":"T20_V1","direction":"UP",
          "endDate":"2026-10-29","modelId":"fixture-model","modelHash":"same-hash","activationRevision":1,
          "dataAsOf":"2026-09-21","featureSnapshot":{"features":{"trendRiskFactor":1,"currentDrawdown":-0.01},
          "facts":[],"missingOptionalFactors":["新闻未接入"]}}],"latestAttempts":[]}
          """));
        when(diagnoses.read(anyString(),any())).thenThrow(new IllegalStateException("fixture optional source unavailable"));
    }
    @AfterEach void clear() { CurrentUserContext.clear(); }

    JsonNode generate(String amount) throws Exception {
        var position=json.treeToValue(json.valueToTree(Map.of("fundCode","123456","fundName","隔离夹具基金",
                "shares",amount,"frozenShares","0","availableShares",amount,"cost",amount,"marketValue",amount)),SimulationTypes.Position.class);
        when(positions.positions(owner)).thenReturn(List.of(position));
        return service.generateFor(owner,"123456",false);
    }
    @Test void tenYuanLargeHoldingAndClearedPositionPreservePublicPrediction() throws Exception {
        var small=generate("10");var large=generate("100000");var cleared=generate("0");
        assertEquals("ADD",small.path("decision").asText());
        assertEquals(small.path("decision"),large.path("decision"));
        assertEquals("BUY",cleared.path("decision").asText());
        assertEquals(small.path("predictionSnapshot"),large.path("predictionSnapshot"));
        assertEquals(small.path("predictionSnapshot"),cleared.path("predictionSnapshot"));
        assertEquals(cleared.path("reportId"),generate("0").path("reportId"));
        assertEquals(3,db.sql("SELECT count(*) FROM portfolio_decision_report WHERE user_id=:u").param("u",owner).query(Integer.class).single());
        assertEquals(small,service.report("123456",UUID.fromString(small.path("reportId").asText())));
    }
}

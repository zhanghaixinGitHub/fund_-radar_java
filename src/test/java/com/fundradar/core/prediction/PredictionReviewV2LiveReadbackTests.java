package com.fundradar.core.prediction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.auth.AccountRole;
import com.fundradar.core.auth.AuthenticatedUser;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.direction1d.Direction1dRepository;
import com.fundradar.core.integration.ai.AiServiceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 显式启用的只读联调：真实Python数据经Java控制器序列化，不读取浏览器会话。 */
@EnabledIfEnvironmentVariable(named="FUND_V2_LIVE_CHECK",matches="true")
class PredictionReviewV2LiveReadbackTests {
    @Test void ninePublishedFundsRoundTripThroughControllerAndRealPythonClient() throws Exception {
        String token=null;
        for(String line:Files.readAllLines(Path.of("../03-python/.env"))) {
            if(line.startsWith("AI_SERVICE_TOKEN=")) {
                token=line.substring(line.indexOf('=')+1).trim();
                if(token.length()>1 && (token.startsWith("\"") && token.endsWith("\"") || token.startsWith("'") && token.endsWith("'")))
                    token=token.substring(1,token.length()-1);
            }
        }
        assertNotNull(token,"本地服务令牌未配置，停止联调");assertFalse(token.isBlank());
        var properties=new AiServiceProperties();properties.setBaseUrl("http://127.0.0.1:8000");properties.setToken(token);
        var json=new ObjectMapper();
        var client=new MultiPredictionClient(RestClient.builder(),properties,json);
        var scope=mock(Direction1dRepository.class);var database=mock(JdbcClient.class);
        var service=new MultiPredictionService(client,scope,database,json);
        var mvc=MockMvcBuilders.standaloneSetup(new MultiPredictionController(service)).build();
        var user=UUID.randomUUID();
        CurrentUserContext.set(new AuthenticatedUser(user,"isolated","联调检查",AccountRole.FUND_USER,
                Set.of(PermissionCode.FUND_READ,PermissionCode.WATCHLIST_SELF_READ)));
        int periods=0,abstain=0;
        try {
            for(String code:new String[]{"001021","001632","002112","005284","006730","007832","013330","019449","021740"}) {
                when(scope.follows(user,code)).thenReturn(true);
                var response=mvc.perform(get("/api/v1/watchlist/"+code+"/predictions/review-v2"))
                        .andExpect(status().isOk()).andReturn().getResponse();
                assertEquals("no-store, private",response.getHeader("Cache-Control"));
                var data=json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
                assertEquals(code,data.path("fundCode").asText());assertEquals(4,data.path("periods").size());
                for(var row:data.path("periods")) {
                    periods++;
                    if("ABSTAIN".equals(row.path("decision").asText())) {abstain++;assertEquals("005284",code);}
                    if("T1".equals(row.path("horizonId").asText())) {
                        assertEquals("RETROSPECTIVE",row.path("kind").asText());assertTrue(row.path("excludedFromAccuracy").asBoolean());
                    }
                    if("M6_V1".equals(row.path("horizonId").asText())) {
                        assertTrue(row.path("endDate").isNull());assertEquals("2027-05-11",row.path("nominalEndDate").asText());
                    }
                }
            }
            assertEquals(36,periods);assertEquals(2,abstain);verifyNoInteractions(database);
        } finally {CurrentUserContext.clear();}
    }
}

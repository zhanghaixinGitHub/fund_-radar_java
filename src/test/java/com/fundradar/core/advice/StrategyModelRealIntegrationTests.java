package com.fundradar.core.advice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.auth.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 显式启用的本机真实模型/行情/数据库验收。Java 不监听新端口，真实 Python 地址由运行参数提供。
 * 认证上下文为隔离测试用户；数据库事务回滚，JSON 证据仅保存在 Git 忽略目录。
 */
@EnabledIfSystemProperty(named="fund.replay.real",matches="true")
@SpringBootTest(properties={"spring.flyway.enabled=false","simulation.enabled=false",
        "portfolio.advice.enabled=false","analysis.delivery.enabled=false","direction1d.initial-delay=PT24H",
        "direction1d.training-enabled=false","direction1d.review-enabled=false","prediction.multi.initial-delay=PT24H",
        "app.portfolio-import.enabled=false"})
@Transactional
class StrategyModelRealIntegrationTests {
    @Autowired StrategyResearchService service;
    @Autowired JdbcClient db;
    @Autowired ObjectMapper json;

    @Test void realModelInferenceLedgerAndDatabaseReadback() throws Exception {
        UUID owner=UUID.randomUUID();
        db.sql("INSERT INTO user_account(user_id,mobile,display_name,password_hash,role,status) VALUES(:id,:mobile,'模型比较事务验收','fixture','FUND_USER','ACTIVE')")
                .param("id",owner).param("mobile","139"+String.format("%08d",ThreadLocalRandom.current().nextInt(100000000))).update();
        CurrentUserContext.set(new AuthenticatedUser(owner,"fixture","fixture",AccountRole.FUND_USER,Set.of(PermissionCode.RESEARCH_RUN_ADMIN)));
        try {
            var result=service.run(new StrategyResearchService.Request("006730",LocalDate.of(2026,1,1),LocalDate.of(2026,9,22)));
            assertEquals("SUCCEEDED",result.path("status").asText(),result.path("errorMessage").asText());
            assertTrue(result.path("comparisons").size()>=2,"至少一个完整真实模型与一直持有");
            assertFalse(result.path("comparisons").has("V1"));assertFalse(result.path("comparisons").has("V2_WITHOUT_EVENTS"));
            var restored=service.read(UUID.fromString(result.path("runId").asText()));
            // 按 JSON 响应比较；PostgreSQL JSONB 会将 1E+4 规范化为 10000，不要求数值节点类型相同。
            assertTrue(json.readTree(result.toString()).equals((left,right)->left.isNumber() && right.isNumber()
                    ? left.decimalValue().compareTo(right.decimalValue()) : left.equals(right)?0:1,restored),
                    "结果保存后读取的字段或数值不一致");
            for(var model:result.path("inputSnapshot").path("modelComparisons")) {
                assertEquals(3,model.path("modelRefs").size());
                assertEquals(result.path("inputSnapshot").path("frames").size(),model.path("frames").size());
            }
            Files.createDirectories(Path.of(".local-runs"));
            Files.writeString(Path.of(".local-runs/model-comparison-real-result.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            CurrentUserContext.clear();
            assertThrows(com.fundradar.core.auth.service.AuthenticationRequiredException.class,
                    ()->service.read(UUID.fromString(result.path("runId").asText())));
        } finally {CurrentUserContext.clear();}
    }
}

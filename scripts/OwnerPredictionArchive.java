import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.direction1d.Direction1dRepository;
import com.fundradar.core.integration.ai.AiServiceProperties;
import com.fundradar.core.prediction.MultiPredictionClient;
import com.fundradar.core.prediction.MultiPredictionService;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import java.time.LocalDate;
import java.util.*;

/**
 * 本机每日任务的正式归档适配。通过已运行项目的类路径执行，不启动第二个Spring服务。
 * 输入仅来自标准输入；不接受账号白名单、固定基金或固定日期，也不记录数据库凭据。
 * 每次先校验有效账号及本人关注，再调用正式方法并逐字核对本人档案。
 */
class OwnerPredictionArchive {
    public static void main(String[] args) throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode request = json.readTree(System.in);
        UUID owner = UUID.fromString(request.path("owner").asText());
        String code = request.path("code").asText();
        UUID id = UUID.fromString(request.path("predictionId").asText());
        LocalDate target = LocalDate.parse(request.path("targetDate").asText());
        if (!code.matches("[0-9]{6}") || !"http://127.0.0.1:8000".equals(request.path("baseUrl").asText()))
            throw new IllegalArgumentException("LOCAL_SCOPE_INVALID");
        var data = new PGSimpleDataSource();
        data.setURL(request.path("jdbcUrl").asText());
        data.setUser(request.path("dbUser").asText());
        data.setPassword(request.path("dbPassword").asText());
        JdbcClient db = JdbcClient.create(data);
        var manager = new DataSourceTransactionManager(data);
        var scope = new Direction1dRepository(db, json, manager);
        var properties = new AiServiceProperties();
        properties.setBaseUrl(request.path("baseUrl").asText());
        properties.setToken(request.path("serviceToken").asText());
        var client = new MultiPredictionClient(RestClient.builder(), properties, json);
        var service = new MultiPredictionService(client, scope, db, json);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            if (!scope.predictionEligible(owner, code)) throw new IllegalStateException("OWNER_FUND_NOT_ELIGIBLE");
            JsonNode original = client.get("/funds/" + code);
            JsonNode selected = null;
            for (var prediction : original.path("predictions")) {
                if (!id.toString().equals(prediction.path("predictionId").asText())) continue;
                if (selected != null) throw new IllegalStateException("DUPLICATE_PUBLIC_RESULT");
                selected = prediction;
            }
            if (selected == null || !code.equals(selected.path("fundCode").asText())
                    || !target.toString().equals(selected.path("startDate").asText())
                    || !"COMPREHENSIVE".equals(selected.path("generationMethod").asText()))
                throw new IllegalStateException("CURRENT_PREDICTION_IDENTITY_OR_DATE_MISMATCH");
            // 正式方法幂等建立本人引用；不直接写预测、评分或其他账号结果。
            service.currentFor(owner, code);
            if (!scope.predictionEligible(owner, code)) throw new IllegalStateException("OWNER_REMOVED_DURING_ARCHIVE");
            String saved = db.sql("SELECT payload::text FROM prediction_user_link WHERE user_id=:u AND fund_code=:c AND prediction_id=:id")
                    .param("u", owner).param("c", code).param("id", id).query(String.class).single();
            try {
                if (!json.readTree(saved).equals(selected)) throw new IllegalStateException("OWNER_PAYLOAD_MISMATCH");
            } catch (java.io.IOException error) { throw new IllegalStateException("OWNER_PAYLOAD_INVALID"); }
        });
        System.out.println("OWNER_ARCHIVE_RESULT:" + json.writeValueAsString(Map.of(
                "owner", owner, "code", code, "predictionId", id, "targetDate", target.toString(), "verified", true)));
    }
}

package com.fundradar.core.advice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.direction1d.Direction1dPolicy;
import com.fundradar.core.prediction.MultiPredictionClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** 读取本人原报告推进同条件账本；不调用今天的赢家重算过去，不提交模拟或真实交易。 */
@Service
public class IssuedAdviceEffectService {
    private final JdbcClient db;private final ObjectMapper json;private final MultiPredictionClient client;
    private final DecisionServiceV2 decisions;private final StrategyReplayEngine engine;
    public IssuedAdviceEffectService(JdbcClient db,ObjectMapper json,MultiPredictionClient client,DecisionServiceV2 decisions,StrategyReplayEngine engine) {
        this.db=db;this.json=json;this.client=client;this.decisions=decisions;this.engine=engine;
    }
    public record Request(String fundCode,LocalDate startDate,LocalDate endDate) {}
    public JsonNode read(Request request) {
        UUID owner=CurrentUserContext.requirePermission(PermissionCode.PORTFOLIO_SELF_READ).userId();
        decisions.owns(owner,request.fundCode());
        StrategyResearchService.validateRequest(new StrategyResearchService.Request(request.fundCode(),request.startDate(),request.endDate()),LocalDate.now(ZoneId.of("Asia/Shanghai")));
        var records=db.sql("""
          SELECT payload::text,content_hash FROM portfolio_decision_report WHERE user_id=:u AND fund_code=:c
          AND generated_at>=CAST(:start AS date)-interval '2 days' AND generated_at<CAST(:end AS date)+interval '1 day'
          ORDER BY generated_at,report_id LIMIT 10001
          """).param("u",owner).param("c",request.fundCode()).param("start",request.startDate()).param("end",request.endDate()).query().listOfRows();
        if(records.size()>10000) throw new IllegalArgumentException("所选区间建议超过一万条，请缩短日期范围");
        ObjectNode result=json.createObjectNode();result.put("mode","ISSUED_ADVICE");result.put("fundCode",request.fundCode());
        result.put("startDate",request.startDate().toString());result.put("endDate",request.endDate().toString());
        result.put("note","按当时实际发出的建议推进隔离模拟账本，与相同本金和费用的一直持有对照；不代表个人真实收益");
        if(records.isEmpty()) {result.put("status","NO_REPORTS");result.put("message","本区间尚无当时发出的建议，不能用今天模型补造");return result;}
        try {
            var input=client.get("/funds/"+request.fundCode()+"/ledger-inputs?start="+request.startDate()+"&end="+request.endDate());
            var config=json.treeToValue(client.get("/auto/policy").path("executionPolicy"),StrategyReplayEngine.Config.class);
            var frames=new ArrayList<StrategyReplayEngine.Frame>();
            for(var raw:input.path("frames")) frames.add(json.treeToValue(raw,StrategyReplayEngine.Frame.class));
            var actions=new LinkedHashMap<LocalDate,StrategyReplayEngine.IssuedDecision>();
            var sequence=new TreeMap<LocalDate,ObjectNode>();int failed=0;
            for(var row:records) {
                var report=json.readTree(row.get("payload").toString());
                if(!"SUCCEEDED".equals(report.path("generationStatus").asText())) {failed++;continue;}
                if(!DecisionPolicyV2.VERSION.equals(report.path("strategyVersion").asText()))
                    throw new IllegalArgumentException("该历史建议的策略版本尚无兼容账本，不能用当前策略代替");
                var issued=Instant.parse(report.path("generatedAt").asText()).atZone(ZoneId.of("Asia/Shanghai"));
                var validUntil=Instant.parse(report.path("validUntil").asText());
                LocalDate first=issued.toLocalTime().isBefore(LocalTime.of(15,0))?issued.toLocalDate():issued.toLocalDate().plusDays(1);
                var day=frames.stream().map(StrategyReplayEngine.Frame::date).filter(d->!d.isBefore(first)).findFirst().orElse(null);
                if(day==null||!day.atTime(15,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant().isBefore(validUntil)) continue;
                actions.put(day,new StrategyReplayEngine.IssuedDecision(report.path("decision").asText(),row.get("content_hash").toString()));
                var identity=json.createObjectNode();identity.put("reportId",report.path("reportId").asText());identity.put("executedOn",day.toString());
                identity.put("generatedAt",issued.toInstant().toString());identity.put("reportHash",row.get("content_hash").toString());
                identity.set("modelRefs",report.path("modelRefs"));identity.set("releaseIds",report.path("releaseIds"));
                sequence.put(day,identity);
            }
            result.put("failedReports",failed);result.put("missingReportDays",frames.size()-actions.size());
            result.put("reportCount",records.size());result.put("effectiveDays",actions.size());
            if(actions.isEmpty()) {result.put("status","WAITING_EXECUTABLE_REPORT");result.put("message","尚无落入该区间且有效的已发出建议，等待新的真实估值日");return result;}
            // 没有任何已发出建议的前置区间不算策略空仓劣势；双方从首个可执行日同额起跑。
            LocalDate firstAction=actions.keySet().stream().min(LocalDate::compareTo).orElseThrow();
            int leading=(int)frames.stream().filter(frame->frame.date().isBefore(firstAction)).count();
            frames=new ArrayList<>(frames.stream().filter(frame->!frame.date().isBefore(firstAction)).toList());
            result.put("actualStartDate",firstAction.toString());result.put("leadingDaysWithoutReports",leading);
            result.put("missingReportDays",frames.size()-actions.size());
            var identities=result.putArray("reportSequence");sequence.values().forEach(identities::add);
            result.set("system",json.valueToTree(engine.run(frames,config,"ISSUED_ADVICE",actions)));
            result.set("buyHold",json.valueToTree(engine.run(frames,config,"BUY_HOLD")));
            result.put("inputHash",input.path("inputHash").asText());result.put("status","SUCCEEDED");
            String hash=Direction1dPolicy.hash(result.toString());result.put("evidenceHash",hash);
            db.sql("""
              INSERT INTO advice_effect_evidence(evidence_id,user_id,fund_code,content_hash,spec,payload)
              VALUES(:id,:u,:c,:hash,CAST(:spec AS jsonb),CAST(:payload AS jsonb)) ON CONFLICT DO NOTHING
              """).param("id",UUID.randomUUID()).param("u",owner).param("c",request.fundCode()).param("hash",hash)
                    .param("spec",json.writeValueAsString(request)).param("payload",result.toString()).update();
            return result;
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalStateException("建议账本证据解析失败",e);}
    }
}

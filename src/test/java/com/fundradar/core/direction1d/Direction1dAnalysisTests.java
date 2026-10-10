package com.fundradar.core.direction1d;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 新分析单独验收，确保不能拿旧日期、改写事实或无依据方向冒充成功。 */
class Direction1dAnalysisTests {
    final ObjectMapper json=new ObjectMapper();
    final Instant now=Instant.parse("2026-10-09T09:00:00Z");
    ObjectNode payload() throws Exception {
        var p=json.createObjectNode();
        p.put("protocol",Direction1dAnalysisPolicy.PROTOCOL);p.put("schema_version",Direction1dAnalysisPolicy.PROTOCOL);
        p.put("fund_code","002112");p.put("fund_name","测试资料");p.put("kind","FORWARD_ORIGINAL");
        p.put("horizon_trading_days",1);p.put("target_definition",Direction1dPolicy.THREE_STATE_TARGET);
        p.put("direction_policy",Direction1dPolicy.THREE_STATE_POLICY);p.put("validation_status","UNVALIDATED");
        p.put("model_released",false);p.putNull("up_probability");p.put("base_nav_date","2026-10-09");p.put("target_nav_date","2026-10-12");
        p.put("window_open_at","2026-10-09T15:00:00+08:00");p.put("deadline_at","2026-10-12T15:00:00+08:00");
        p.put("as_of","2026-10-09T16:00:00+08:00");p.put("generated_at","2026-10-09T16:01:00+08:00");
        p.put("expires_at","2027-10-09T16:00:00+08:00");p.put("calendar_version",Direction1dCalendar.VERSION);
        p.put("revision_sequence",1);p.put("task_key","DIRECTION_1D_ANALYSIS_V1:002112:2026-10-12:r1");
        p.put("input_identity","a".repeat(64));p.put("input_hash","b".repeat(64));p.put("snapshot_hash","b".repeat(64));
        p.put("input_snapshot_id",UUID.randomUUID().toString());p.put("cohort_id","test");p.putNull("latest_nav_date");
        var input=p.putObject("input");input.put("fund_code","002112");input.put("feature_as_of",p.path("as_of").asText());
        input.put("source_id",UUID.randomUUID().toString());input.putArray("values");
        var fact=p.putObject("evidence").putObject("holdings");fact.put("category","持仓行情");fact.put("text","披露持仓所对应的已发生行情偏弱");fact.putNull("source");
        var a=p.putObject("analysis");a.put("direction","DOWN");a.put("confidence","LOW");a.put("summary","倾向下跌，把握较低。");a.put("synthesis","若弱势延续，仍有压力。");
        a.putArray("counterpoints");a.putArray("limitations").add("尚未取得最近净值。");a.putArray("change_conditions").add("最新行情转强。");
        var reason=a.putArray("reasons").addObject();reason.put("title","持仓走弱");reason.put("role","支持下跌");reason.put("meaning","已发生行情偏弱。");reason.put("implication","若延续会拖累。");reason.putArray("refs").add("holdings");
        var n=p.putObject("narrative");n.put("styleVersion","PREDICTION_INFORMATION_ZH_V4");n.set("summary",a.get("summary"));n.set("context",a.get("synthesis"));
        n.put("supporting","");n.put("opposing","");n.set("conditions",a.get("change_conditions"));n.set("limitations",a.get("limitations"));n.putArray("counterpoints");
        var driver=n.putArray("drivers").addObject();driver.set("title",reason.get("title"));driver.set("meaning",reason.get("meaning"));driver.set("implication",reason.get("implication"));
        driver.set("assessment",reason.get("role"));driver.set("category",fact.get("category"));driver.set("observation",fact.get("text"));driver.put("relation","");driver.putArray("sources");
        var inventory=p.putArray("inventory");for(int i=1;i<=16;i++)inventory.addObject().put("id",String.format("D%02d",i)).put("name","资料").put("detail","已检查");n.set("inventory",inventory);
        return p;
    }
    void validate(ObjectNode p) {String raw=p.toString();Direction1dPolicy.validate(json,raw,Direction1dPolicy.hash(raw),"002112",now);}
    @Test void missingLatestNavStillKeepsFixedComparisonDate() throws Exception {assertDoesNotThrow(()->validate(payload()));}
    /** 经理背景沿用同一份原事实与来源契约；前端说明不得另行改写离任原因或任期表现。 */
    @Test void managerEvidenceKeepsExactNarrativeAndSource() throws Exception {
        var p=payload();
        var fact=((ObjectNode)p.path("evidence")).putObject("manager:performance");
        fact.put("category","基金经理");
        fact.put("text","陆阳任期包含共同管理；独立管理表现单列，基准比较暂缺。");
        fact.put("relation","本基金的管理连续性与任期表现。");
        fact.put("direction_eligible",false);
        var source=fact.putObject("source");
        source.put("title","基金经理变更公告");source.put("url","https://example.com/change.pdf");
        source.put("publishedDate","2026-05-30");
        var reason=((ObjectNode)p.path("analysis")).withArray("reasons").addObject();
        reason.put("title","经理变更与表现");reason.put("role","背景观察");
        reason.put("meaning","公开原因按原文理解，任期收益不能全部归于个人。");
        reason.put("implication","只作管理背景，不能机械决定次日方向。");
        reason.putArray("refs").add("manager:performance");
        var driver=((ObjectNode)p.path("narrative")).withArray("drivers").addObject();
        for(String field:new String[]{"title","meaning","implication"}) driver.set(field,reason.get(field));
        driver.set("category",fact.get("category"));driver.set("observation",fact.get("text"));
        driver.set("relation",fact.get("relation"));driver.set("assessment",reason.get("role"));
        driver.putArray("sources").add(source.deepCopy());
        assertDoesNotThrow(()->validate(p));
        driver.put("observation","经理能力已经证明优秀。");
        assertThrows(IllegalArgumentException.class,()->validate(p));
    }
    @Test void directionMustHaveSupportingReason() throws Exception {var p=payload();((ObjectNode)p.get("analysis")).put("direction","UP");assertThrows(IllegalArgumentException.class,()->validate(p));}
    @Test void narrativeCannotChangeFactsOrAddSource() throws Exception {
        var p=payload();((ObjectNode)p.path("narrative").path("drivers").get(0)).put("observation","改成其他数字");assertThrows(IllegalArgumentException.class,()->validate(p));
        var q=payload();((ObjectNode)q.path("narrative").path("drivers").get(0)).withArray("sources").addObject().put("url","https://example.com");assertThrows(IllegalArgumentException.class,()->validate(q));
    }
    @Test void afterDeadlineAndProbabilityAreRejected() throws Exception {
        var p=payload();p.put("generated_at","2026-10-12T15:00:00+08:00");assertThrows(IllegalArgumentException.class,()->validate(p));
        var q=payload();q.put("up_probability",.8);assertThrows(IllegalArgumentException.class,()->validate(q));
    }
    /** 同公司多文件归入一份依据后，Java 必须校验每份原文，不能只接受第一份。 */
    ObjectNode bundlePayload() throws Exception {
        var p=payload();var fact=(ObjectNode)p.path("evidence").path("holdings");
        fact.put("event_group","issuer-evidence:test");fact.put("text","逐字保留不同事项原文。".repeat(400));
        var sources=fact.putArray("sources");
        for(int i=0;i<2;i++)sources.addObject().put("title","同公司公告"+i).put("url","https://example.com/notice/"+i).put("publishedDate","2026-10-09");
        fact.set("source",sources.get(0).deepCopy());
        var driver=(ObjectNode)p.path("narrative").path("drivers").get(0);
        driver.set("sources",sources.deepCopy());driver.set("observation",fact.get("text"));
        return p;
    }
    @Test void allOriginalSourcesAndLongQuoteBundleAreAccepted() throws Exception {
        assertDoesNotThrow(()->validate(bundlePayload()));
    }
    @Test void omittedOrChangedSecondarySourceIsRejected() throws Exception {
        var p=bundlePayload();((com.fasterxml.jackson.databind.node.ArrayNode)p.path("narrative").path("drivers").get(0).path("sources")).remove(1);
        assertThrows(IllegalArgumentException.class,()->validate(p));
        var q=bundlePayload();((ObjectNode)q.path("narrative").path("drivers").get(0).path("sources").get(1)).put("title","其他公告");
        assertThrows(IllegalArgumentException.class,()->validate(q));
    }
    @Test void secondarySourceMustHaveSafeUrlAndAvailableDate() throws Exception {
        for(String field:new String[]{"url","publishedDate"}) {
            var p=bundlePayload();var sources=p.path("evidence").path("holdings").path("sources");
            ((ObjectNode)sources.get(1)).put(field,field.equals("url")?"file:///secret":"2026-10-10");
            ((ObjectNode)p.path("narrative").path("drivers").get(0)).set("sources",sources.deepCopy());
            assertThrows(IllegalArgumentException.class,()->validate(p));
        }
    }
    @Test void duplicateOrMismatchedPrimarySourceIsRejected() throws Exception {
        var p=bundlePayload();var fact=(ObjectNode)p.path("evidence").path("holdings");
        fact.withArray("sources").add(fact.path("source").deepCopy());assertThrows(IllegalArgumentException.class,()->validate(p));
        var q=bundlePayload();((ObjectNode)q.path("evidence").path("holdings").path("source")).put("title","错配公告");
        assertThrows(IllegalArgumentException.class,()->validate(q));
    }
    @Test void replayEvidenceWhenSupplied() throws Exception {
        String path=System.getProperty("analysis.replay.fixture");if(path==null)return;
        var rows=json.readTree(Files.readString(Path.of(path)));assertFalse(rows.isEmpty());
        for(var row:rows) {
            var p=payload();p.set("evidence",row.get("evidence"));p.set("analysis",row.get("analysis"));
            var narrative=row.get("narrative").deepCopy();((ObjectNode)narrative).set("inventory",p.get("inventory"));p.set("narrative",narrative);
            assertDoesNotThrow(()->validate(p),row.path("target").asText());
        }
    }
    @Test void realLocalPayloadWhenSupplied() throws Exception {
        String path=System.getProperty("analysis.fixture");if(path==null)return;
        String raw=Files.readString(Path.of(path));assertDoesNotThrow(()->Direction1dPolicy.validate(json,raw,Direction1dPolicy.hash(raw),"002112",Instant.now()));
    }
}

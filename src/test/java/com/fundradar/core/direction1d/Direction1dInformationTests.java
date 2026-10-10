package com.fundradar.core.direction1d;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

/** 综合输入不能仅改页面文案：两端必须核对扩展字段、原净值、来源时间和基金范围。 */
class Direction1dInformationTests {
    final ObjectMapper json = new ObjectMapper();

    ObjectNode payload() throws Exception {
        var p = new Direction1dThreeStateTests().payload("UP");
        p.put("fund_code", "002112");
        var input = (ObjectNode)p.path("input");
        input.put("fund_code", "002112");
        input.put("feature_version", "002112_FULL_INFORMATION_V1");
        var information = input.putObject("information");
        information.put("version", "002112_FULL_INFORMATION_V1");
        information.put("text", "已保存的公告、政策和新闻原文片段");
        information.put("source_identity", "a".repeat(64));
        information.put("market_date", p.path("base_nav_date").asText());
        var values = information.putArray("numeric");
        input.path("features").forEach(values::add);
        for(int i=7;i<87;i++) values.addNull();
        information.putArray("sources").addObject().put("kind", "NEWS")
                .put("source_hash", "b".repeat(64)).put("available_at", input.path("feature_as_of").asText());
        return p;
    }

    void validate(ObjectNode p, String code) {
        String input = p.path("input").toString();
        p.put("input_json", input); p.put("input_hash", Direction1dPolicy.hash(input));
        String raw = p.toString();
        Direction1dPolicy.validate(json, raw, Direction1dPolicy.hash(raw), code, Instant.parse("2026-09-11T12:00:00Z"));
    }

    @Test void acceptsBoundedFullInputAndPreservesMissingValues() throws Exception {
        assertDoesNotThrow(()->validate(payload(), "002112"));
    }

    @Test void rejectsFutureNewsWrongFundAndNavMismatch() throws Exception {
        var future = payload();
        ((ObjectNode)future.path("input").path("information").path("sources").get(0))
                .put("available_at", "2099-01-01T00:00:00Z");
        assertThrows(IllegalArgumentException.class, ()->validate(future, "002112"));
        var other = payload(); other.put("fund_code", "001412");
        assertThrows(IllegalArgumentException.class, ()->validate(other, "001412"));
        var mismatch = payload();
        ((com.fasterxml.jackson.databind.node.ArrayNode)mismatch.path("input").path("information").path("numeric"))
                .set(0, json.getNodeFactory().numberNode(999));
        assertThrows(IllegalArgumentException.class, ()->validate(mismatch, "002112"));
    }
}

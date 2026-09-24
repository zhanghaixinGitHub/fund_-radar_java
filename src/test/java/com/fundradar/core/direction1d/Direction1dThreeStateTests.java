package com.fundradar.core.direction1d;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 三类必须由三类分数得出；旧档案继续通过旧协议校验，不能仅改方向文字。 */
class Direction1dThreeStateTests {
    final ObjectMapper json=new ObjectMapper();
    ObjectNode payload(String winner) throws Exception {
        var p=(ObjectNode)json.readTree(new Direction1dTests().payload());
        p.put("protocol",Direction1dPolicy.ACTIVE_PROTOCOL);
        p.put("schema_version","DIRECTION_1D_EXPERIMENT_V2");
        p.put("target_definition",Direction1dPolicy.THREE_STATE_TARGET);
        p.put("direction_policy",Direction1dPolicy.THREE_STATE_POLICY);
        for(var node:p.path("branches")) {
            var branch=(ObjectNode)node;branch.put("predicted_direction",winner);branch.put("score",.6);
            var scores=branch.putObject("class_scores");
            for(String key:List.of("UP","FLAT","DOWN"))scores.put(key,key.equals(winner)?.6:.2);
        }
        return p;
    }
    void validate(ObjectNode p) {
        String raw=p.toString();
        Direction1dPolicy.validate(json,raw,Direction1dPolicy.hash(raw),"001632",Instant.parse("2026-09-11T12:00:00Z"));
    }
    @Test void acceptsAllThreeIndependentDirections() throws Exception {
        for(String key:List.of("UP","FLAT","DOWN"))assertDoesNotThrow(()->validate(payload(key)));
    }
    @Test void rejectsRelabelledBinaryWrongPolicyOrTamperedScores() throws Exception {
        var p=payload("DOWN");var branch=(ObjectNode)p.path("branches").get(0);
        branch.remove("class_scores");assertThrows(IllegalArgumentException.class,()->validate(p));
        var bad=payload("FLAT");((ObjectNode)bad.path("branches").get(0)).put("predicted_direction","DOWN");
        assertThrows(IllegalArgumentException.class,()->validate(bad));
        var policy=payload("UP");policy.put("direction_policy","ARBITRARY_FLAT_BAND");
        assertThrows(IllegalArgumentException.class,()->validate(policy));
    }
    @Test void flatWinsExactTieAndMaximumScoreIsChecked() throws Exception {
        var branch=(ObjectNode)payload("FLAT").path("branches").get(0);
        branch.putObject("class_scores").put("DOWN",.1).put("FLAT",.45).put("UP",.45);branch.put("score",.45);
        assertEquals("FLAT",Direction1dPolicy.threeStateWinner(branch));
        branch.put("score",.7);assertThrows(IllegalArgumentException.class,()->Direction1dPolicy.threeStateWinner(branch));
    }
}

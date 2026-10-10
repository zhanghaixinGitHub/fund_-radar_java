package com.fundradar.core.direction1d;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 暂不判断是保留原依据的业务结果，既不是持平，也不能混入旧模型或伪造方向。 */
class Direction1dAbstentionTests {
    final Direction1dInformationTests helper=new Direction1dInformationTests();
    ObjectNode payload() throws Exception {
        var p=helper.payload();
        p.put("activation_policy",Direction1dPolicy.ACTIVATION_POLICY);
        var input=(ObjectNode)p.path("input");
        input.put("feature_version","002112_EVENT_EVIDENCE_V2");
        var full=(ObjectNode)input.path("information");
        full.put("version","002112_EVENT_EVIDENCE_V2");
        var evidence=full.putObject("event_evidence");
        evidence.put("version","002112_EVENT_EVIDENCE_V2").put("overflow",false).put("positive",0).put("negative",0);
        evidence.putArray("events"); evidence.putArray("values").add(0).add(0).add(0).add(0);
        for(var value:p.path("branches")) {
            var branch=(ObjectNode)value;
            branch.put("status","ABSTAINED").putNull("score").putNull("predicted_direction").putNull("class_scores");
            branch.put("registered_at",branch.path("trained_at").asText());
            branch.put("model_selected_at",branch.path("trained_at").asText());
            branch.putObject("decision").put("policy","002112_EVENT_EVIDENCE_V2")
                    .putArray("reason_codes").add("NO_DIRECTIONAL_EVENT").add("VALIDATION_INSUFFICIENT");
        }
        return p;
    }
    @Test void acceptsTwoAbstainedBranchesWithoutAnyInventedDirection() throws Exception {
        assertDoesNotThrow(()->helper.validate(payload(),"002112"));
    }
    @Test void rejectsDisguisedFlatUnknownReasonAndOldVersion() throws Exception {
        var flat=payload(); ((ObjectNode)flat.path("branches").get(0)).put("predicted_direction","FLAT");
        assertThrows(IllegalArgumentException.class,()->helper.validate(flat,"002112"));
        var reason=payload(); ((ObjectNode)reason.path("branches").get(0).path("decision")).putArray("reason_codes").add("MADE_UP");
        assertThrows(IllegalArgumentException.class,()->helper.validate(reason,"002112"));
        var old=payload(); ((ObjectNode)old.path("input")).put("feature_version","002112_FULL_INFORMATION_V1");
        assertThrows(IllegalArgumentException.class,()->helper.validate(old,"002112"));
    }
    @Test void rejectsTamperedEventTotalsAndMissingQualityNumbers() throws Exception {
        var p=payload(); ((ObjectNode)p.path("input").path("information").path("event_evidence")).put("positive",99);
        assertThrows(IllegalArgumentException.class,()->helper.validate(p,"002112"));
        var missing=payload(); ((ObjectNode)missing.path("input").path("information").path("event_evidence")).remove("positive");
        assertThrows(IllegalArgumentException.class,()->helper.validate(missing,"002112"));
    }
}

package com.fundradar.core.advice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.fundradar.core.advice.DecisionPolicyV2.*;

/** 同账本条件、实际推理身份及旧对照撤除的契约测试。 */
class StrategyModelComparisonTests {
    final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());
    final StrategyReplayEngine engine=new StrategyReplayEngine(new DecisionPolicyV2(json));
    final StrategyResearchService service=new StrategyResearchService(null,null,json,engine);

    ObjectNode inputs() {
        var root=json.createObjectNode();root.put("comparisonVersion","MODEL_BUNDLE_REPLAY_V1");
        root.putArray("excludedModels");var base=root.putArray("frames");
        for(int day=5;day<8;day++) base.add(json.valueToTree(new StrategyReplayEngine.Frame(LocalDate.of(2026,1,day),
                BigDecimal.ONE,BigDecimal.ZERO,false,new Input(List.of(),0.0,List.of(),List.of(),false,"BALANCED",true,null,0.0,null,List.of()))));
        var bundles=root.putArray("modelComparisons");
        for(String key:List.of("a","b")) {
            var bundle=bundles.addObject();bundle.put("id","MODEL_"+key.repeat(16));bundle.put("label",key);
            bundle.put("role",key.equals("a")?"CURRENT":"CANDIDATE");var refs=bundle.putArray("modelRefs");
            for(String horizon:List.of("T5_V1","T20_V1","M6_V1"))
                refs.addObject().put("horizonId",horizon).put("modelId",key+horizon).put("modelHash",key+horizon).put("activationRevision",1);
            var frames=bundle.putArray("frames");
            for(var original:base) {
                ObjectNode frame=original.deepCopy();var signals=((ObjectNode)frame.path("input")).putArray("predictions");
                for(var ref:refs) signals.addObject().put("horizonId",ref.path("horizonId").asText())
                        .put("modelId",ref.path("modelId").asText()).put("modelHash",ref.path("modelHash").asText())
                        .put("direction",key.equals("a")?"UP":"DOWN").put("predictionId","prediction")
                        .put("activationRevision",1).put("dataAsOf","2026-01-02")
                        .put("targetDefinitionId",com.fundradar.core.prediction.PredictionDirectionContract.TARGET)
                        .put("directionPolicyHash",com.fundradar.core.prediction.PredictionDirectionContract.HASH);
                frames.add(frame);
            }
        }
        return root;
    }

    @Test void comparesHoldAndEachRealModelWithoutOldRulesOrEventAblation() throws Exception {
        var result=service.compareModels(inputs());
        assertEquals(3,result.path("comparisons").size());
        assertFalse(result.path("comparisons").has("V1"));assertFalse(result.has("eventAdoptionDecision"));
        assertEquals(1,result.path("comparisons").path("MODEL_"+"a".repeat(16)).path("tradeCount").asInt());
        assertEquals(0,result.path("comparisons").path("MODEL_"+"b".repeat(16)).path("tradeCount").asInt());
        assertEquals(3,result.path("comparisonModels").path("MODEL_"+"b".repeat(16)).path("modelRefs").size());
    }

    @Test void rejectsModelIdentityMismatchAndIncompletePrediction() {
        for(boolean missing:List.of(true,false)) {
            var input=inputs();var signals=(com.fasterxml.jackson.databind.node.ArrayNode)input.path("modelComparisons").get(0).path("frames").get(0).path("input").path("predictions");
            if(missing) signals.remove(0);else ((ObjectNode)signals.get(0)).put("modelId","fallback-baseline");
            assertThrows(IllegalArgumentException.class,()->service.compareModels(input));
        }
    }

    @Test void rejectsDifferentDatesAndRiskInputs() {
        var input=inputs();((ObjectNode)input.path("modelComparisons").get(0).path("frames").get(0).path("input")).put("trendRisk",0.9);
        assertThrows(IllegalArgumentException.class,()->service.compareModels(input));
        var other=inputs();((com.fasterxml.jackson.databind.node.ArrayNode)other.path("modelComparisons").get(0).path("frames")).remove(0);
        assertThrows(IllegalArgumentException.class,()->service.compareModels(other));
    }

    @Test void oldInputVersionCannotMasqueradeAsTrainedModelComparison() {
        var input=inputs();input.remove("comparisonVersion");
        assertThrows(IllegalArgumentException.class,()->service.compareModels(input));
    }
}

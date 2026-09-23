package com.fundradar.core.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.direction1d.Direction1dPolicy;
import java.util.*;

/** 新建议只接受已冻结的三分类口径；旧二分类由历史读取与原动作回放单独保留。 */
public final class PredictionDirectionContract {
    public static final String TARGET = "NEXT_EXECUTABLE_CASH_REINVESTED_THREE_STATE_V2";
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final JsonNode RULE = loadRule();
    public static final String HASH = Direction1dPolicy.hash(canonical(RULE));
    private PredictionDirectionContract() {}

    private static JsonNode loadRule() {
        try (var stream = PredictionDirectionContract.class.getResourceAsStream("/prediction-policy-v2.json")) {
            var root = JSON.readTree(stream);
            if (!TARGET.equals(root.path("target_definition_id").asText())) throw new IllegalStateException("目标配置错误");
            return root.path("direction");
        } catch (Exception e) { throw new IllegalStateException("三分类规则无法读取", e); }
    }

    /** 与Python稳定JSON相同：对象键排序、数组顺序保留；阈值以十进制字符串传输。 */
    private static String canonical(JsonNode node) {
        if (node.isObject()) {
            var keys = new TreeSet<String>(); node.fieldNames().forEachRemaining(keys::add);
            var parts = new ArrayList<String>();
            for (var key : keys) parts.add(JSON.getNodeFactory().textNode(key).toString() + ":" + canonical(node.get(key)));
            return "{" + String.join(",", parts) + "}";
        }
        if (node.isArray()) { var parts = new ArrayList<String>(); node.forEach(v -> parts.add(canonical(v))); return "[" + String.join(",", parts) + "]"; }
        return node.toString();
    }

    public static boolean valid(String direction, String target, String hash) {
        return direction != null && Set.of("UP", "FLAT", "DOWN").contains(direction)
                && TARGET.equals(target) && HASH.equals(hash);
    }

    public static void require(JsonNode prediction) {
        String horizon = prediction.path("horizonId").asText();
        if (!valid(prediction.path("direction").asText(), prediction.path("targetDefinitionId").asText(),
                prediction.path("directionPolicyHash").asText())
                || !RULE.equals(prediction.path("directionPolicySnapshot"))
                || !RULE.path("thresholds").has(horizon)
                || !RULE.path("thresholds").path(horizon).asText().equals(prediction.path("flatThreshold").asText()))
            throw new IllegalArgumentException("预测目标或持平规则不兼容");
    }
}

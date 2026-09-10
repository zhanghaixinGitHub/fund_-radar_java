package com.fundradar.core.watchlist.api;

import com.fundradar.core.integration.ai.AiDirectionExperiment;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 实验模型分数独立于正式预测；服务端验证基金、日期、版本及两个模型身份。 */
public record DirectionExperimentResponse(
        String fundCode, String version, String status, boolean modelReleased, int horizonTradingDays,
        UUID researchRunId, LocalDate cutoffDate, LocalDate latestNavDate,
        LocalDate targetBaseDate, LocalDate targetEndDate, Instant readAt,
        String inputHash, UUID sourceRevisionId, List<ModelScore> models, List<String> reasonCodes, String message
) {
    private static final UUID RUN = UUID.fromString("0c0e06a9-725e-4b68-b813-de6ff5124b29");
    private static final List<String> BRANCHES = List.of("DROP_60D_GROUP_L2", "REFERENCE");
    private static final List<String> HASHES = List.of(
            "b08efdc7bc8fc04833d0c3d6ed1edcbd7c3caff080e4787db837cef04f17660f",
            "0146d0ff0ac0504d23a428c0f607d16636e457110ac67c6e60d1b7b7dea19789");

    public record ModelScore(String branch, double score, String direction, String modelHash, LocalDate fitEnd) { }

    public static DirectionExperimentResponse from(AiDirectionExperiment source, String fund) {
        if (source == null || !fund.equals(source.fundCode())
                || !"DIRECTION_PAGE_TRIAL_V1".equals(source.version())
                || !Boolean.FALSE.equals(source.modelReleased())
                || !Integer.valueOf(20).equals(source.horizonTradingDays())
                || !RUN.equals(source.researchRunId()) || source.readAt() == null
                || source.status() == null
                || !List.of("EXPERIMENTAL", "DATA_INSUFFICIENT", "NOT_APPLICABLE", "UNAVAILABLE").contains(source.status())
                || source.models() == null || source.models().size() > 2
                || source.reasonCodes() == null || source.reasonCodes().size() > 12
                || source.reasonCodes().stream().anyMatch(v -> v == null || v.length() > 150)
                || source.message() == null || source.message().length() > 300) {
            throw new IllegalArgumentException("Invalid experiment projection");
        }
        if ("EXPERIMENTAL".equals(source.status())) {
            if (!List.of("001632", "006730", "008888").contains(fund)
                    || source.models().size() != 2 || !source.reasonCodes().isEmpty()
                    || source.cutoffDate() == null || source.latestNavDate() == null || source.targetEndDate() == null
                    || !source.cutoffDate().equals(source.targetBaseDate())
                    || !source.latestNavDate().isBefore(source.cutoffDate())
                    || !source.targetEndDate().isAfter(source.cutoffDate())
                    || source.inputHash() == null || !source.inputHash().matches("[0-9a-f]{64}")
                    || source.sourceRevisionId() == null) {
                throw new IllegalArgumentException("Invalid experiment dates or identity");
            }
            for (int i = 0; i < 2; i++) {
                var model = source.models().get(i);
                if (model == null || !BRANCHES.get(i).equals(model.branch()) || !HASHES.get(i).equals(model.modelHash())
                        || !LocalDate.of(2024, 3, 31).equals(model.fitEnd())
                        || !model.fitEnd().isBefore(source.latestNavDate())
                        || model.score() == null || !Double.isFinite(model.score()) || model.score() < 0 || model.score() > 1
                        || !(model.score() > 0.5 ? "UP" : "NON_UP").equals(model.direction())) {
                    throw new IllegalArgumentException("Invalid experiment model score");
                }
            }
        } else if (!source.models().isEmpty() || source.reasonCodes().isEmpty()) {
            throw new IllegalArgumentException("Unavailable experiment contains scores");
        }
        var models = source.models().stream()
                .map(m -> new ModelScore(m.branch(), m.score(), m.direction(), m.modelHash(), m.fitEnd())).toList();
        return new DirectionExperimentResponse(fund, source.version(), source.status(), false, 20, RUN,
                source.cutoffDate(), source.latestNavDate(), source.targetBaseDate(), source.targetEndDate(),
                source.readAt(), source.inputHash(), source.sourceRevisionId(), models,
                List.copyOf(source.reasonCodes()), source.message());
    }

    public static DirectionExperimentResponse unavailable(String fund) {
        return new DirectionExperimentResponse(fund, "DIRECTION_PAGE_TRIAL_V1", "UNAVAILABLE", false, 20,
                RUN, null, null, null, null, Instant.now(), null, null, List.of(),
                List.of("EXPERIMENT_SERVICE_UNAVAILABLE"), "实验模型服务暂时无法读取，请稍后重试。");
    }
}

package com.fundradar.core.fund.api;

import java.util.List;

/** 公共评级 DTO 白名单；透传正式展示分和业务依据，计算权重及研究结果留在后台。 */
public record FundRatingResponse(
        String fundCode, String status, String grade, String gradeLabel, String asOfDate,
        String validUntil, String ratingRef, String message, String summary, String score,
        Comparison comparison, List<Dimension> dimensions, List<Point> strengths, List<Point> weaknesses,
        Dates dataDates, List<String> limitations, Summary previousRating,
        ScoreRange scoreRange, String partialScore, Coverage scoreCoverage) {
    public record Summary(String fundCode, String status, String grade, String gradeLabel,
            String asOfDate, String validUntil, String ratingRef, String message,
            ScoreRange scoreRange, String partialScore, Coverage scoreCoverage) {
        /** 保留既有完整评分摘要调用；新增部分结果只由Python同批次数据提供。 */
        public Summary(String fundCode, String status, String grade, String gradeLabel,
                String asOfDate, String validUntil, String ratingRef, String message) {
            this(fundCode, status, grade, gradeLabel, asOfDate, validUntil, ratingRef, message, null, null, null);
        }
    }
    /** 已发布分数范围，未取中点；Java不再推算单点或等级。 */
    public record ScoreRange(String lower, String upper) { }
    /** 原八维权重覆盖，百分数；不同覆盖率的部分分不能当作完整总分排名。 */
    public record Coverage(String pointWeight, String intervalWeight, String missingWeight,
            int pointDimensions, int intervalDimensions) { }
    public record Batch(List<Summary> items) { }
    public record Comparison(String category, String currency, int productCount, String scope, String period) { }
    public record Metric(String name, String value, String unit, String period) { }
    public record Source(String title, String url, String publishedOn) { }
    /** score为同正式批次的展示分字符串；无有效评分时为空，Java不另行计算或判级。 */
    public record Dimension(String key, String name, String grade, String gradeLabel, String score, String explanation,
            List<Metric> metrics, List<Source> sources, ScoreRange scoreRange) { }
    public record Point(String dimension, String text) { }
    public record Dates(String nav, String holdings, String manager, String fees) { }
}

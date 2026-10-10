package com.fundradar.core.fund.api;

import java.util.List;

/** 公共评级 DTO 白名单；仅基金业务依据，不承接内部得分、权重或方法配置。 */
public record FundRatingResponse(
        String fundCode, String status, String grade, String gradeLabel, String asOfDate,
        String validUntil, String ratingRef, String message, String summary,
        Comparison comparison, List<Dimension> dimensions, List<Point> strengths, List<Point> weaknesses,
        Dates dataDates, List<String> limitations, Summary previousRating) {
    public record Summary(String fundCode, String status, String grade, String gradeLabel,
            String asOfDate, String validUntil, String ratingRef, String message) { }
    public record Batch(List<Summary> items) { }
    public record Comparison(String category, String currency, int productCount, String scope, String period) { }
    public record Metric(String name, String value, String unit, String period) { }
    public record Source(String title, String url, String publishedOn) { }
    public record Dimension(String key, String name, String grade, String gradeLabel, String explanation,
            List<Metric> metrics, List<Source> sources) { }
    public record Point(String dimension, String text) { }
    public record Dates(String nav, String holdings, String manager, String fees) { }
}

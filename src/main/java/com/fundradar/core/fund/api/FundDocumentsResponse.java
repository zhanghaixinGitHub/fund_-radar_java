package com.fundradar.core.fund.api;

import java.util.List;

/** 公告分页元数据；来源标题不代表已审核的事件摘要，文字未完整提取不等于 PDF 缺失。 */
public record FundDocumentsResponse(List<Document> items, int total, int page, int pageSize, String asOfDate) {
    public record Document(String id, String kind, String title, String stockCode, String stockName,
            String publishedDate, String dateNote, String sourceName, String sourceUrl,
            boolean textComplete, boolean latestHeld) {}
}

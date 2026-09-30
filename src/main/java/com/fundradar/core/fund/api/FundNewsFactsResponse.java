package com.fundradar.core.fund.api;

import java.util.List;

/** 公共消息的业务白名单；不向浏览器透传研究资格、模型字段或原始解析日志。 */
public record FundNewsFactsResponse(String fundCode,String checkedAt,boolean complete,
        List<Item> items,List<String> limitations) {
    /** 公开日期和实际取得时间分别保留；stage 仅表示经核对的披露阶段。 */
    public record Item(String eventId,String title,String sourceName,String sourceUrl,String publishedDate,
            String firstReceivedAt,String stage,String summary,String relation,
            @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
            String evidenceHash) {}
}

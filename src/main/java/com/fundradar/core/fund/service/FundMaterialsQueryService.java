package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundMaterialsResponse;
import com.fundradar.core.fund.api.FundDocumentsResponse;
import com.fundradar.core.integration.ai.AiFundClient;
import org.springframework.stereotype.Service;

/** 协调公共资料读取；不会因打开页面触发采集、大模型调用或预测。 */
@Service
public class FundMaterialsQueryService {
    private final AiFundClient client;
    public FundMaterialsQueryService(AiFundClient client) { this.client = client; }
    public FundMaterialsResponse overview(String code, String reportId, String stockCode) {
        return client.getFundMaterials(code, reportId, stockCode);
    }
    public FundDocumentsResponse documents(String code, int page, int size, String kind,
            String stockCode, String keyword, boolean latestOnly) {
        return client.getFundDocuments(code, page, size, kind, stockCode, keyword, latestOnly);
    }
}

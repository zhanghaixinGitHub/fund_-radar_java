package com.fundradar.core.fund.api;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 非股票持仓可穿过 Java 白名单；旧快照没有新字段时继续可读。 */
class FundMaterialsContractTests {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test void preservesFundAndBondTablesWithoutInventingAStockWeight() {
        var value = mapper.readValue("""
                {"available":true,"fundCode":"021740","reports":[],"companies":[],
                 "report":{"fullDisclosure":false,"holdings":[],"assets":[],"industries":[],"stockWeightPct":null,
                  "investmentGroups":[{"name":"基金持仓","notice":"披露持仓",
                   "holdings":[{"stockCode":"","stockName":"目标基金","weightPct":92.85,
                                "marketValue":1284080458.56}]}],"holdingsNotice":"不代表实时持仓"}}
                """, FundMaterialsResponse.class);
        assertNull(value.report().stockWeightPct());
        assertEquals("目标基金", value.report().investmentGroups().get(0).holdings().get(0).stockName());
        assertTrue(mapper.writeValueAsString(value).contains("不代表实时持仓"));
    }

    @Test void acceptsHistoricalSnapshotWithoutNewFields() {
        var value = mapper.readValue("""
                {"available":true,"fundCode":"002112","reports":[],"companies":[],
                 "report":{"fullDisclosure":true,"holdings":[],"assets":[],"industries":[],"stockWeightPct":80.5}}
                """, FundMaterialsResponse.class);
        assertNull(value.report().investmentGroups());
        assertNull(value.report().holdingsNotice());
        assertEquals("80.5", value.report().stockWeightPct().toPlainString());
    }
}

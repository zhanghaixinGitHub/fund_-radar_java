package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundDetailResponse;
import com.fundradar.core.fund.api.FundPageResponse;
import com.fundradar.core.fund.api.FundSummaryResponse;
import com.fundradar.core.integration.ai.AiFundClient;
import com.fundradar.core.integration.ai.AiFundDetail;
import com.fundradar.core.integration.ai.AiFundPage;
import com.fundradar.core.integration.ai.AiFundSummary;
import org.springframework.stereotype.Service;

/** M0 implementation that obtains only mock read models through the internal client. */
@Service
public class InternalFundQueryService implements FundQueryService {

    private final AiFundClient aiFundClient;

    public InternalFundQueryService(AiFundClient aiFundClient) {
        this.aiFundClient = aiFundClient;
    }

    @Override
    public FundPageResponse listFunds(String keyword, int pageSize, String cursor) {
        AiFundPage page = aiFundClient.listFunds(keyword, pageSize, cursor);
        return new FundPageResponse(
                page.items().stream().map(this::toSummaryResponse).toList(),
                page.nextCursor()
        );
    }

    @Override
    public FundDetailResponse getFund(String fundCode) {
        AiFundDetail fund = aiFundClient.getFund(fundCode);
        return new FundDetailResponse(
                fund.fundCode(),
                fund.fundName(),
                fund.fundType(),
                fund.status(),
                fund.asOfDate(),
                fund.navStatus(),
                fund.dataSource()
        );
    }

    private FundSummaryResponse toSummaryResponse(AiFundSummary fund) {
        return new FundSummaryResponse(
                fund.fundCode(),
                fund.fundName(),
                fund.fundType(),
                fund.status(),
                fund.asOfDate()
        );
    }
}

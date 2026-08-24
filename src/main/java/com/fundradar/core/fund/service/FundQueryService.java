package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundDetailResponse;
import com.fundradar.core.fund.api.FundPageResponse;

/** Coordinates public fund queries without exposing the Python service to browsers. */
public interface FundQueryService {

    FundPageResponse listFunds(String keyword, int pageSize, String cursor);

    FundDetailResponse getFund(String fundCode);
}

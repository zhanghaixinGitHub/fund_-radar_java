package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundRatingResponse;
import com.fundradar.core.integration.ai.AiFundClient;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 公共评级只读协调：固定批量上限和首次出现顺序；不读取用户持仓，不在 Java 重新判级。 */
@Service
public class FundRatingQueryService {
    private final AiFundClient client;
    public FundRatingQueryService(AiFundClient client) { this.client = client; }

    public FundRatingResponse.Batch batch(String codes) {
        if (codes == null || !codes.matches("[0-9]{6}(,[0-9]{6}){0,99}"))
            throw new IllegalArgumentException("请提供1至100个六位基金代码。");
        return client.getFundRatings(Arrays.stream(codes.split(",")).distinct().collect(Collectors.joining(",")));
    }

    public FundRatingResponse detail(String code, String reference) {
        if (code == null || !code.matches("[0-9]{6}")
                || (reference != null && !reference.matches("[a-f0-9]{64}")))
            throw new IllegalArgumentException("基金代码或所选评级无效。");
        return client.getFundRating(code, reference);
    }
}

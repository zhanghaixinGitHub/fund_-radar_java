package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundShareClassesResponse;
import com.fundradar.core.integration.ai.AiFundClient;
import com.fundradar.core.integration.ai.AiServiceUnavailableException;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 读取公开关联事实并校验基金身份；失败时不回退前端旧名单或伪造无其他份额。 */
@Service
public class FundShareClassQueryService {
    private final AiFundClient client;
    public FundShareClassQueryService(AiFundClient client) { this.client = client; }

    public FundShareClassesResponse read(String code) {
        var value = client.getFundShareClasses(code);
        if (value == null || !code.equals(value.fundCode()) || value.status() == null
                || !Set.of("VERIFIED", "STALE", "UNVERIFIED").contains(value.status())
                || value.shares() == null || value.shares().size() > 30) throw unavailable();
        Set<String> members = new HashSet<>();
        for (var share : value.shares()) {
            if (share == null || share.fundCode() == null || !share.fundCode().matches("[0-9]{6}")
                    || share.fundName() == null || share.fundName().isBlank()
                    || !members.add(share.fundCode())) throw unavailable();
        }
        if ("UNVERIFIED".equals(value.status())) {
            if (!members.isEmpty()) throw unavailable();
        } else if (!members.contains(code) || value.asOfDate() == null || value.checkedAt() == null) {
            throw unavailable();
        }
        return value;
    }

    private AiServiceUnavailableException unavailable() {
        return new AiServiceUnavailableException("份额资料暂时无法获取，请稍后重试。", null);
    }
}

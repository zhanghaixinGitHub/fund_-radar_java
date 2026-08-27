package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundSyncResponse;
import com.fundradar.core.integration.ai.AiFocusedNavSyncResult;
import com.fundradar.core.integration.ai.AiFundClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/** 将 Java 对外人工同步请求受控转发至 Python 内部同步服务。 */
@Service
public class InternalFundSyncService implements FundSyncService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InternalFundSyncService.class);

    private final AiFundClient aiFundClient;

    public InternalFundSyncService(AiFundClient aiFundClient) {
        this.aiFundClient = aiFundClient;
    }

    @Override
    public FundSyncResponse syncFocusedNavIncremental() {
        AiFocusedNavSyncResult result = aiFundClient.syncFocusedNavIncremental();
        FundSyncResponse response = toResponse(result);
        LOGGER.info(
                "InternalFundSyncService.syncFocusedNavIncremental   >>> manual focused NAV sync completed, "
                        + "runId={}, fetched={}, created={}, updated={}, skipped={}",
                response.syncRunId(), response.fetchedCount(), response.createdCount(),
                response.updatedCount(), response.skippedCount()
        );
        return response;
    }

    /** 将 Python 的蛇形内部契约转换为浏览器使用的驼峰响应。 */
    static FundSyncResponse toResponse(AiFocusedNavSyncResult result) {
        return new FundSyncResponse(
                result.syncRunId(),
                result.requestedNavDate(),
                List.copyOf(result.fundCodes()),
                result.fetchedCount(),
                result.createdCount(),
                result.updatedCount(),
                result.skippedCount()
        );
    }
}

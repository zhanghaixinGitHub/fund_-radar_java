package com.fundradar.core.analysis.service;

import com.fundradar.core.analysis.api.AnalysisSignalDeliveryResponse;
import com.fundradar.core.integration.ai.AiSignalChangePage;
import com.fundradar.core.integration.ai.AiSignalClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 受控拉取 Python 已发布评分，并委托 JDBC Writer 在本地事务内完成投递。 */
@Service
public class DefaultAnalysisSignalDeliveryService implements AnalysisSignalDeliveryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultAnalysisSignalDeliveryService.class);
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES_PER_RUN = 100;
    private final AiSignalClient aiSignalClient;
    private final JdbcAnalysisSignalDeliveryWriter deliveryWriter;

    public DefaultAnalysisSignalDeliveryService(
            AiSignalClient aiSignalClient,
            JdbcAnalysisSignalDeliveryWriter deliveryWriter
    ) {
        this.aiSignalClient = aiSignalClient;
        this.deliveryWriter = deliveryWriter;
    }

    @Override
    /** 每页先读取远端，再以本地事务完整写入；最大页数防止持续新增导致无限运行。 */
    public AnalysisSignalDeliveryResponse deliverAvailableSignals() {
        int fetchedCount = 0;
        int processedCount = 0;
        int signalUpsertedCount = 0;
        int notificationCreatedCount = 0;
        int skippedRuleCount = 0;
        AnalysisDeliveryCheckpoint checkpoint = deliveryWriter.getCheckpoint();
        boolean hasMore = false;

        for (int pageNumber = 0; pageNumber < MAX_PAGES_PER_RUN; pageNumber++) {
            AiSignalChangePage page = aiSignalClient.listActiveScoredChanges(
                    checkpoint.lastScoredAt(), checkpoint.lastForecastId(), PAGE_SIZE
            );
            fetchedCount += page.items().size();
            if (page.items().isEmpty()) {
                hasMore = false;
                break;
            }
            JdbcAnalysisSignalDeliveryWriter.DeliveryPageResult result = deliveryWriter.deliverChanges(page.items());
            processedCount += result.processedCount();
            signalUpsertedCount += result.signalUpsertedCount();
            notificationCreatedCount += result.notificationCreatedCount();
            skippedRuleCount += result.skippedRuleCount();
            checkpoint = result.checkpoint();
            hasMore = page.hasMore();
            if (!hasMore) {
                break;
            }
        }

        LOGGER.info(
                "DefaultAnalysisSignalDeliveryService.deliverAvailableSignals   >>> fetched={}, processed={}, signals={}, notifications={}, hasMore={}",
                fetchedCount, processedCount, signalUpsertedCount, notificationCreatedCount, hasMore
        );
        return new AnalysisSignalDeliveryResponse(
                fetchedCount,
                processedCount,
                signalUpsertedCount,
                notificationCreatedCount,
                skippedRuleCount,
                checkpoint.lastScoredAt(),
                checkpoint.lastForecastId(),
                hasMore
        );
    }
}

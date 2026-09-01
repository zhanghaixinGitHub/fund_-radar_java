package com.fundradar.core.analysis.scheduler;

import com.fundradar.core.analysis.config.AnalysisDeliveryProperties;
import com.fundradar.core.analysis.service.AnalysisDeliveryInProgressException;
import com.fundradar.core.analysis.service.AnalysisSignalDeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 可选的评分投递调度器；默认关闭，且永不启动 Python 评分、回测或模型发布。 */
@Component
public class AnalysisSignalDeliveryScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalysisSignalDeliveryScheduler.class);
    private final AnalysisDeliveryProperties properties;
    private final AnalysisSignalDeliveryService analysisSignalDeliveryService;

    public AnalysisSignalDeliveryScheduler(
            AnalysisDeliveryProperties properties,
            AnalysisSignalDeliveryService analysisSignalDeliveryService
    ) {
        this.properties = properties;
        this.analysisSignalDeliveryService = analysisSignalDeliveryService;
    }

    /** 周期消费已有 ACTIVE 评分；未显式启用时无副作用返回。 */
    @Scheduled(fixedDelayString = "${analysis.delivery.fixed-delay:PT5M}")
    public void deliverScheduledSignals() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            analysisSignalDeliveryService.deliverAvailableSignals();
        } catch (AnalysisDeliveryInProgressException exception) {
            LOGGER.info("AnalysisSignalDeliveryScheduler.deliverScheduledSignals   >>> another delivery is already active");
        } catch (RuntimeException exception) {
            LOGGER.error("AnalysisSignalDeliveryScheduler.deliverScheduledSignals   >>> scheduled delivery failed", exception);
        }
    }
}

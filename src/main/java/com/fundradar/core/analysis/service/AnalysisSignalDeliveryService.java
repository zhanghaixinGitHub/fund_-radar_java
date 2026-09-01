package com.fundradar.core.analysis.service;

import com.fundradar.core.analysis.api.AnalysisSignalDeliveryResponse;

/** 将 Python 已发布评分受控投递到 Java 信号快照和当前用户通知。 */
public interface AnalysisSignalDeliveryService {

    /** 消费当前可见的评分增量；不会启动评分、回测、发布或任何交易。 */
    AnalysisSignalDeliveryResponse deliverAvailableSignals();
}

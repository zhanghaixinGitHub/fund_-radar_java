package com.fundradar.core.analysis.service;

/** 另一个节点已经获得投递锁时抛出，避免并发推进同一个消费游标。 */
public class AnalysisDeliveryInProgressException extends RuntimeException {

    public AnalysisDeliveryInProgressException() {
        super("analysis signal delivery is already in progress");
    }
}

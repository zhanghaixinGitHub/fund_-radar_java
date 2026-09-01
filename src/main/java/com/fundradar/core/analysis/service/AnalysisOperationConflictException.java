package com.fundradar.core.analysis.service;

/** Python 拒绝重复回测或不满足发布状态机时抛出，供对外层映射为稳定 409。 */
public class AnalysisOperationConflictException extends RuntimeException {

    public AnalysisOperationConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}

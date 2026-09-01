package com.fundradar.core.analysis.service;

/** 请求的持久分析运行不存在时抛出，避免错误映射为分析服务整体不可用。 */
public class AnalysisRunNotFoundException extends RuntimeException {

    public AnalysisRunNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}

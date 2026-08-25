package com.fundradar.core.integration.ai;

/** Python AI 内部服务无法提供必需读模型时抛出的业务异常。 */
public class AiServiceUnavailableException extends RuntimeException {

    /** 使用面向调用方的说明和底层原因构造异常；底层原因仅写入服务端日志。 */
    public AiServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

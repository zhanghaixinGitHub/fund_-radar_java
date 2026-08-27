package com.fundradar.core.integration.ai;

/** Python 检测到重点基金历史基线缺失时抛出的受控前置条件异常。 */
public class FocusedNavSyncBaselineMissingException extends RuntimeException {

    public FocusedNavSyncBaselineMissingException(String message, Throwable cause) {
        super(message, cause);
    }
}

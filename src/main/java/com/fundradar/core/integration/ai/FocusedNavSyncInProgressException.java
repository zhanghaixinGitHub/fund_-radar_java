package com.fundradar.core.integration.ai;

/** Python 拒绝重复运行重点基金净值同步时抛出的受控冲突异常。 */
public class FocusedNavSyncInProgressException extends RuntimeException {

    public FocusedNavSyncInProgressException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.fundradar.core.integration.ai;

/** Python 已收到手动同步请求但 Tushare 同步失败时抛出的受控异常。 */
public class FocusedNavSyncFailedException extends RuntimeException {

    public FocusedNavSyncFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}

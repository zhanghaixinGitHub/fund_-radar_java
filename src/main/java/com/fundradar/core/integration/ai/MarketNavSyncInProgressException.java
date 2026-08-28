package com.fundradar.core.integration.ai;

/** Python 拒绝重复运行基金市场净值同步时抛出的受控冲突异常。 */
public class MarketNavSyncInProgressException extends RuntimeException {

    public MarketNavSyncInProgressException(String message, Throwable cause) {
        super(message, cause);
    }
}

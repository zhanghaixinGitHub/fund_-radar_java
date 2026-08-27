package com.fundradar.core.integration.ai;

/** Python 进程重启后找不到本机内存任务时抛出的受控异常。 */
public class SyncJobNotFoundException extends RuntimeException {

    public SyncJobNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.fundradar.core.integration.ai;

import java.net.SocketTimeoutException;
import org.springframework.web.client.HttpStatusCodeException;

/** 公共依赖失败的稳定分类；只返回状态与请求号，不透传响应正文、连接串或凭据。 */
public final class PublicDataFailure {
    private PublicDataFailure() {}
    public record Error(String code, String stage, String summary, boolean retryable, String traceId) {}
    public static Error classify(Throwable error, String stage, String traceId) {
        for (Throwable current=error; current!=null; current=current.getCause()) {
            if(current instanceof SocketTimeoutException || current instanceof java.net.http.HttpTimeoutException)
                return new Error("UPSTREAM_TIMEOUT",stage,"公共数据服务请求超时，请稍后重试。",true,traceId);
        }
        if(error instanceof HttpStatusCodeException http) {
            int status=http.getStatusCode().value();
            return new Error(status==401 || status==403 ? "UPSTREAM_AUTH_FAILED" : "UPSTREAM_UNAVAILABLE",stage,
                    "公共数据服务返回 HTTP "+status+"，本次未取得所需数据。",status>=500,traceId);
        }
        return new Error("UPSTREAM_UNAVAILABLE",stage,"公共数据服务连接失败或响应无效，请稍后重试。",true,traceId);
    }
}

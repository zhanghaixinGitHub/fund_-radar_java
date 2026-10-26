package com.fundradar.core.integration.ai;

import com.fundradar.core.common.trace.TraceContext;
import com.fundradar.core.fund.api.FundManagerDirectory;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

/** Java到Python的经理资料客户端：固定内部路径，查询与采集分开，POST不自动重试。 */
@Service
public class AiFundManagerDirectoryClient {
    public static class DirectoryException extends RuntimeException {
        private final int status;
        public DirectoryException(int status) { this.status = status; }
        public int status() { return status; }
    }
    private final RestClient client;
    private final AiServiceProperties properties;
    public AiFundManagerDirectoryClient(RestClient.Builder builder, AiServiceProperties properties) {
        this.properties = properties;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout());
        factory.setReadTimeout(properties.getReadTimeout());
        client = builder.baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
    }

    public FundManagerDirectory.Page list(String keyword, String company, String fundType, String state,
            int page, int size, UUID snapshot) {
        return get(uri -> {
            uri.path("/internal/v1/fund-managers").queryParam("keyword", "{keyword}")
                    .queryParam("company", "{company}").queryParam("fundType", fundType)
                    .queryParam("state", state).queryParam("page", page).queryParam("pageSize", size);
            if (snapshot != null) uri.queryParam("snapshotId", snapshot);
            return uri.build(Map.of("keyword", keyword, "company", company));
        }, FundManagerDirectory.Page.class);
    }
    public FundManagerDirectory.Detail detail(UUID id, UUID snapshot) {
        return get(uri -> {
            uri.path("/internal/v1/fund-managers/{id}");
            if (snapshot != null) uri.queryParam("snapshotId", snapshot);
            return uri.build(id);
        }, FundManagerDirectory.Detail.class);
    }
    /** 本基金经理卡片一次读取全部稳定编号，避免逐人请求或全局按姓名猜人。 */
    @SuppressWarnings("unchecked")
    public Map<String, String> links(String fundCode) {
        return get(uri -> uri.path("/internal/v1/fund-managers/links").queryParam("fundCode", fundCode).build(), Map.class);
    }
    public FundManagerDirectory.FundPage funds(UUID id, String state, int page, int size, UUID snapshot) {
        return get(uri -> {
            uri.path("/internal/v1/fund-managers/{id}/funds").queryParam("state", state)
                    .queryParam("page", page).queryParam("pageSize", size);
            if (snapshot != null) uri.queryParam("snapshotId", snapshot);
            return uri.build(id);
        }, FundManagerDirectory.FundPage.class);
    }
    public FundManagerDirectory.RelatedDetail related(UUID id, UUID fundId, UUID snapshot) {
        return get(uri -> {
            uri.path("/internal/v1/fund-managers/{id}/funds/{fund}");
            if (snapshot != null) uri.queryParam("snapshotId", snapshot);
            return uri.build(id, fundId);
        }, FundManagerDirectory.RelatedDetail.class);
    }
    public FundManagerDirectory.Fund performance(UUID id, UUID fundId, UUID tenureId, UUID snapshot) {
        return get(uri -> {
            uri.path("/internal/v1/fund-managers/{id}/performance").queryParam("relatedFundId", fundId)
                    .queryParam("tenureVersionId", tenureId);
            if (snapshot != null) uri.queryParam("snapshotId", snapshot);
            return uri.build(id);
        }, FundManagerDirectory.Fund.class);
    }
    public AiSyncJobStatus latest() {
        return get(uri -> uri.path("/internal/v1/fund-managers/sync/latest").build(), AiSyncJobStatus.class, true);
    }
    public AiSyncJobStatus start(FundManagerDirectory.SyncRequest body) {
        try {
            var value = client.post().uri("/internal/v1/fund-managers/sync")
                    .header("X-Service-Token", properties.getToken()).header("X-Trace-Id", TraceContext.getTraceId())
                    .body(body).retrieve().body(AiSyncJobStatus.class);
            if (value == null) throw new AiServiceUnavailableException("Empty manager task", null);
            return value;
        } catch (RestClientResponseException error) { throw convert(error); }
        catch (AiServiceUnavailableException error) { throw error; }
        catch (RuntimeException error) { throw new AiServiceUnavailableException("经理资料服务暂不可用", error); }
    }
    private <T> T get(Function<UriBuilder, URI> uri, Class<T> type) { return get(uri, type, false); }
    private <T> T get(Function<UriBuilder, URI> uri, Class<T> type, boolean nullable) {
        try {
            T value = client.get().uri(uri).header("X-Service-Token", properties.getToken())
                    .header("X-Trace-Id", TraceContext.getTraceId()).retrieve().body(type);
            if (value == null && !nullable) throw new AiServiceUnavailableException("Empty manager data", null);
            return value;
        } catch (RestClientResponseException error) { throw convert(error); }
        catch (AiServiceUnavailableException error) { throw error; }
        catch (RuntimeException error) { throw new AiServiceUnavailableException("经理资料服务暂不可用", error); }
    }
    private RuntimeException convert(RestClientResponseException error) {
        int status = error.getStatusCode().value();
        if (status == 404 || status == 409) return new DirectoryException(status);
        if (status == 422 || status == 400) return new IllegalArgumentException("基金经理查询条件不正确。");
        return new AiServiceUnavailableException("基金经理资料暂不可用", error);
    }
}

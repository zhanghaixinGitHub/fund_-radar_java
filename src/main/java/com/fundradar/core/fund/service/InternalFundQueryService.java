package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundDetailResponse;
import com.fundradar.core.fund.api.FundDividendResponse;
import com.fundradar.core.fund.api.FundManagerResponse;
import com.fundradar.core.fund.api.FundNavHistoryResponse;
import com.fundradar.core.fund.api.FundNavPointResponse;
import com.fundradar.core.fund.api.FundPageResponse;
import com.fundradar.core.fund.api.FundShareSnapshotResponse;
import com.fundradar.core.fund.api.FundSummaryResponse;
import com.fundradar.core.fund.api.WatchlistFundDetailResponse;
import com.fundradar.core.integration.ai.AiFundClient;
import com.fundradar.core.integration.ai.AiFundDetail;
import com.fundradar.core.integration.ai.AiFundWatchlistDetail;
import com.fundradar.core.integration.ai.AiFundNavHistory;
import com.fundradar.core.integration.ai.AiFundPage;
import com.fundradar.core.integration.ai.AiFundSummary;
import com.fundradar.core.integration.ai.AiServiceUnavailableException;
import com.fundradar.core.fund.cache.RedisFundReadCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * FundQueryService 的 M0 实现。
 *
 * 正常情况下通过内部客户端读取 Python 的基金读模型并写入 Redis；仅在 AI 服务不可用时读取同维度缓存，并显式标记 stale。
 */
@Service
public class InternalFundQueryService implements FundQueryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InternalFundQueryService.class);

    private final AiFundClient aiFundClient;
    private final RedisFundReadCache fundReadCache;

    public InternalFundQueryService(AiFundClient aiFundClient, RedisFundReadCache fundReadCache) {
        this.aiFundClient = aiFundClient;
        this.fundReadCache = fundReadCache;
    }

    @Override
    /** 查询基金分页；无缓存的 AI 服务异常会继续抛出，不能伪造列表结果。 */
    public FundPageResponse listFunds(String keyword, String fundType, int pageSize, String cursor, Integer page) {
        try {
            AiFundPage responsePage = aiFundClient.listFunds(keyword, fundType, pageSize, cursor, page);
            FundPageResponse response = toPageResponse(responsePage);
            fundReadCache.savePage(keyword, fundType, pageSize, cursor, page, response);
            return response;
        } catch (AiServiceUnavailableException exception) {
            return fundReadCache.findPage(keyword, fundType, pageSize, cursor, page)
                    .map(cached -> {
                        LOGGER.warn("InternalFundQueryService.listFunds   >>> serving stale fund page from cache");
                        return new FundPageResponse(
                                cached.data().items(),
                                cached.data().nextCursor(),
                                cached.data().page(),
                                cached.data().pageSize(),
                                cached.data().totalCount(),
                                cached.data().totalPages(),
                                true,
                                cached.cachedAt()
                        );
                    })
                    .orElseThrow(() -> exception);
        }
    }

    @Override
    /** 查询基金详情；无缓存的 AI 服务异常会继续抛出，基金不存在异常不参与缓存降级。 */
    public FundDetailResponse getFund(String fundCode) {
        try {
            AiFundDetail fund = aiFundClient.getFund(fundCode);
            FundDetailResponse response = toDetailResponse(fund);
            fundReadCache.saveDetail(fundCode, response);
            return response;
        } catch (AiServiceUnavailableException exception) {
            return fundReadCache.findDetail(fundCode)
                    .map(cached -> {
                        LOGGER.warn("InternalFundQueryService.getFund   >>> serving stale fund detail from cache, fundCode={}", fundCode);
                        return cached.data().withClientState(false, true, cached.cachedAt());
                    })
                    .orElseThrow(() -> exception);
        }
    }

    @Override
    /**
     * 查询关注后完整详情；调用方已经完成当前用户关系校验，因此这里不接收用户参数。
     * 完整详情不写共享 Redis，避免任何授权结论与用户态进入缓存。
     */
    public WatchlistFundDetailResponse getWatchlistFundDetail(String fundCode) {
        AiFundWatchlistDetail fund = aiFundClient.getFundWatchlistDetail(fundCode);
        return toWatchlistFundDetailResponse(fund);
    }

    @Override
    /** 查询历史净值；无缓存的 AI 服务异常继续抛出，不能伪造走势图。 */
    public FundNavHistoryResponse getFundNavHistory(String fundCode, LocalDate startDate, LocalDate endDate) {
        try {
            AiFundNavHistory history = aiFundClient.getFundNavHistory(fundCode, startDate, endDate);
            FundNavHistoryResponse response = toNavHistoryResponse(history);
            fundReadCache.saveNavHistory(fundCode, startDate, endDate, response);
            return response;
        } catch (AiServiceUnavailableException exception) {
            return fundReadCache.findNavHistory(fundCode, startDate, endDate)
                    .map(cached -> {
                        LOGGER.warn(
                                "InternalFundQueryService.getFundNavHistory   >>> serving stale NAV history from cache, fundCode={}",
                                fundCode
                        );
                        return new FundNavHistoryResponse(cached.data().items(), true, cached.cachedAt());
                    })
                    .orElseThrow(() -> exception);
        }
    }

    /** 将 Python 内部详情转换为 Java 对外详情，并标记为实时结果。 */
    static FundDetailResponse toDetailResponse(AiFundDetail fund) {
        return new FundDetailResponse(
                fund.fundCode(),
                fund.fundName(),
                fund.fundType(),
                fund.status(),
                fund.asOfDate(),
                fund.unitNav(),
                fund.accumulatedNav(),
                fund.navAnnDate(),
                fund.accumulatedDividend(),
                fund.netAsset(),
                fund.totalNetAsset(),
                fund.adjustedNav(),
                fund.navStatus(),
                fund.dataSource(),
                fund.dayChangeRate(),
                fund.weekChangeRate(),
                fund.monthChangeRate(),
                fund.profileStatus(),
                fund.profileDataSource(),
                fund.managementCompanyName(),
                fund.custodianName(),
                fund.foundDate(),
                fund.dueDate(),
                fund.listDate(),
                fund.issueDate(),
                fund.delistDate(),
                fund.issueAmount(),
                fund.managementFee(),
                fund.custodianFee(),
                fund.durationYear(),
                fund.parValue(),
                fund.minPurchaseAmount(),
                fund.expectedReturn(),
                fund.benchmark(),
                fund.investType(),
                fund.sourceFundType(),
                fund.trusteeName(),
                fund.purchaseStartDate(),
                fund.redemptionStartDate(),
                fund.market(),
                false,
                false,
                null
        );
    }

    /** 将 Python 完整详情转换为 Java 对外契约；该结果尚未写入用户态。 */
    static WatchlistFundDetailResponse toWatchlistFundDetailResponse(AiFundWatchlistDetail fund) {
        return new WatchlistFundDetailResponse(
                toDetailResponse(fund.basic()),
                fund.managersStatus(),
                fund.managers().stream()
                        .map(item -> new FundManagerResponse(
                                item.managerName(), item.annDate(), item.beginDate(), item.endDate(),
                                item.education(), item.dataSource()
                        ))
                        .toList(),
                fund.latestShareStatus(),
                fund.latestShare() == null ? null : new FundShareSnapshotResponse(
                        fund.latestShare().tradeDate(), fund.latestShare().fundShare(), fund.latestShare().dataSource()
                ),
                fund.dividendsStatus(),
                fund.dividends().stream()
                        .map(item -> new FundDividendResponse(
                                item.annDate(), item.implementationAnnDate(), item.baseDate(), item.processStatus(),
                                item.recordDate(), item.exDate(), item.payDate(), item.earningsPayDate(),
                                item.navExDate(), item.cashDividend(), item.baseUnit(), item.distributableEarnings(),
                                item.earningsAmount(), item.reinvestmentArrivalDate(), item.baseYear(), item.dataSource()
                        ))
                        .toList(),
                false,
                null
        );
    }

    /** 将 Python 历史净值映射为 Java 对外契约，不修改小数精度。 */
    static FundNavHistoryResponse toNavHistoryResponse(AiFundNavHistory history) {
        return new FundNavHistoryResponse(
                history.items().stream()
                        .map(point -> new FundNavPointResponse(
                                point.navDate(), point.unitNav(), point.accumulatedNav()
                        ))
                        .toList(),
                false,
                null
        );
    }

    /** 将 Python 内部分页契约转换为浏览器使用的页码和总数契约。 */
    static FundPageResponse toPageResponse(AiFundPage page) {
        return new FundPageResponse(
                page.items().stream().map(InternalFundQueryService::toSummaryResponse).toList(),
                page.nextCursor(),
                page.page(),
                page.pageSize(),
                page.totalCount(),
                page.totalPages(),
                false,
                null
        );
    }

    /** 将 Python 内部摘要转换为 Java 对外列表项。 */
    private static FundSummaryResponse toSummaryResponse(AiFundSummary fund) {
        return new FundSummaryResponse(
                fund.fundCode(),
                fund.fundName(),
                fund.fundType(),
                fund.status(),
                fund.asOfDate(),
                fund.dayChangeRate(),
                fund.weekChangeRate(),
                fund.monthChangeRate(),
                false
        );
    }
}

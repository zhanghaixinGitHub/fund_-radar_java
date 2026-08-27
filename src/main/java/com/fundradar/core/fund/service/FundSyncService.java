package com.fundradar.core.fund.service;

import com.fundradar.core.fund.api.FundSyncResponse;

/** 协调受控的重点基金手动同步，不向浏览器暴露 Python 或 Tushare 凭据。 */
public interface FundSyncService {

    /** 手动补齐当前配置的重点基金日净值，并返回本次同步统计。 */
    FundSyncResponse syncFocusedNavIncremental();
}

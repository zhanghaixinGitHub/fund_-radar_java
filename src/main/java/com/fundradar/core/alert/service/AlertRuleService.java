package com.fundradar.core.alert.service;

import com.fundradar.core.alert.api.AlertRuleResponse;
import com.fundradar.core.alert.api.AlertRulePageResponse;
import com.fundradar.core.alert.api.UpsertAlertRuleRequest;

import java.util.List;

/**
 * 当前用户资讯型提醒规则服务接口。
 *
 * 只负责规则的读取和维护，不承担消息发送、交易执行或模型计算。
 */
public interface AlertRuleService {

    /** 查询当前用户的提醒规则，按最近更新时间倒序返回。 */
    List<AlertRuleResponse> listCurrentUserRules();

    /** 按接收状态分页读取；enabled 为 null 表示全部，页码从 1 开始，越界回到最后一页。 */
    AlertRulePageResponse pageCurrentUserRules(Boolean enabled, int page, int pageSize);

    /** 校验请求后按基金与提醒类型幂等保存规则，并记录审计信息。 */
    AlertRuleResponse upsertCurrentUserRule(UpsertAlertRuleRequest request);
}

package com.fundradar.core.alert.api;

/**
 * 站内提醒能否生成消息，与用户是否愿意接收分别表达。
 * eventFundCodes 保留供旧调用方兼容，公告检查按当前关注基金执行。
 * 可用仅表示生成通路已开启，不保证一定有符合条件的新内容。
 */
public record AlertAvailabilityResponse(boolean eventAvailable, java.util.Set<String> eventFundCodes,
                                        boolean signalChangeAvailable, boolean riskLevelAvailable) {}

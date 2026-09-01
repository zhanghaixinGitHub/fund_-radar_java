package com.fundradar.core.analysis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 分析信号投递调度配置；默认关闭，需由环境配置显式启用。 */
@ConfigurationProperties(prefix = "analysis.delivery")
public class AnalysisDeliveryProperties {

    private boolean enabled = false;

    /** 返回是否允许定时消费者主动投递已有评分。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 由环境配置显式设置投递开关。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}

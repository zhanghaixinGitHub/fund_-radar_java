package com.fundradar.core.portfolio.importer;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 仅供本机维护命令使用的持仓快照导入开关；默认关闭。 */
@ConfigurationProperties(prefix = "app.portfolio-import")
public class LocalPortfolioImportProperties {

    private boolean enabled;
    private String file;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }
}

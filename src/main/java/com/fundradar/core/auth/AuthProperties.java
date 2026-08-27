package com.fundradar.core.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 认证会话及首位管理员的受保护配置；密码只能来自环境变量或未提交的本地配置。 */
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    private String sessionCookieName = "fund_radar_session";
    private String csrfCookieName = "fund_radar_csrf";
    private Duration sessionTtl = Duration.ofHours(8);
    private boolean cookieSecure;
    private String initialAdminMobile = "";
    private String initialAdminPassword = "";
    private String initialAdminDisplayName = "系统管理员";

    public String getSessionCookieName() {
        return sessionCookieName;
    }

    public void setSessionCookieName(String sessionCookieName) {
        this.sessionCookieName = sessionCookieName;
    }

    public String getCsrfCookieName() {
        return csrfCookieName;
    }

    public void setCsrfCookieName(String csrfCookieName) {
        this.csrfCookieName = csrfCookieName;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    public String getInitialAdminMobile() {
        return initialAdminMobile;
    }

    public void setInitialAdminMobile(String initialAdminMobile) {
        this.initialAdminMobile = initialAdminMobile;
    }

    public String getInitialAdminPassword() {
        return initialAdminPassword;
    }

    public void setInitialAdminPassword(String initialAdminPassword) {
        this.initialAdminPassword = initialAdminPassword;
    }

    public String getInitialAdminDisplayName() {
        return initialAdminDisplayName;
    }

    public void setInitialAdminDisplayName(String initialAdminDisplayName) {
        this.initialAdminDisplayName = initialAdminDisplayName;
    }
}

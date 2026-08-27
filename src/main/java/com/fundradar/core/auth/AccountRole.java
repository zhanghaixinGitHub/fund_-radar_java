package com.fundradar.core.auth;

/** 系统账号角色；角色判断始终在 Java 服务端执行，前端仅据此调整可见导航。 */
public enum AccountRole {
    FUND_USER,
    DATA_OPERATOR,
    SYSTEM_ADMIN
}

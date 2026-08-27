package com.fundradar.core.auth;

import java.util.UUID;

/** 旧单用户版本使用的固定账户标识；仅用于保留待确认归属的历史数据。 */
public final class LegacyAccount {

    public static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** 工具类禁止实例化。 */
    private LegacyAccount() {
    }
}

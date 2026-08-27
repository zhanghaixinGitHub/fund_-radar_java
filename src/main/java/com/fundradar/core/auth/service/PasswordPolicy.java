package com.fundradar.core.auth.service;

/** 创建或重置系统密码时使用的长度与空白字符规则。 */
public final class PasswordPolicy {

    private static final int MINIMUM_LENGTH = 6;
    private static final int MAXIMUM_LENGTH = 20;

    /** 工具类禁止实例化。 */
    private PasswordPolicy() {
    }

    /** 校验 6 至 20 位长度，允许用户自行选择字符组合，但拒绝纯空白密码。 */
    public static void validate(String password) {
        if (password == null || password.length() < MINIMUM_LENGTH || password.length() > MAXIMUM_LENGTH) {
            throw new IllegalArgumentException("密码长度必须为 6 至 20 个字符。");
        }
        if (password.isBlank()) {
            throw new IllegalArgumentException("密码不能全部为空白字符。");
        }
    }
}

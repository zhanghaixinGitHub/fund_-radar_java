package com.fundradar.core.integration.ai;

/** 请求的基金代码在 Python AI 读模型中不存在时抛出的业务异常。 */
public class FundNotFoundException extends RuntimeException {

    /** 使用未找到的基金代码构造异常，后续由统一异常处理器转换为公开 404 响应。 */
    public FundNotFoundException(String fundCode) {
        super("Fund not found: " + fundCode);
    }
}

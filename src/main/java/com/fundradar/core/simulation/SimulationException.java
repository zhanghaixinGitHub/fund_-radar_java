package com.fundradar.core.simulation;

/** 稳定且可展示的模拟交易业务拒绝，绝不包含个人金额或来源凭据。 */
public class SimulationException extends RuntimeException {
    private final String code;
    public SimulationException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}

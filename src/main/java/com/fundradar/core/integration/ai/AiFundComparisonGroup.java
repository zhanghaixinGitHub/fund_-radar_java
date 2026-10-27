package com.fundradar.core.integration.ai;

/** Python 已核实的实际同类标准；名称和说明都是业务中文，不由 Java 猜测或重分组。 */
public record AiFundComparisonGroup(String name, String description, String basis) {
}

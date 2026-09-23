package com.fundradar.core.prediction;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 持久化JsonNode不跨到Boot 4的Jackson 3 Web层，输出标准Map/List/标量。 */
public final class PredictionWebData {
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private PredictionWebData() {}
    public static Object of(Object value) {return value==null?null:JSON.convertValue(value,Object.class);}
}

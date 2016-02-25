package org.swisspush.gateleen.core.json;

import io.vertx.core.json.JsonObject;

import java.util.Map;

/**
 * Contains functions for json handling.
 * 
 * @author https://github.com/floriankammermann [Florian Kammermann]
 */
public final class JsonUtil {

    private JsonUtil(){}

    public static boolean containsNoNestedProperties(JsonObject jsonObject) {
        Map<String, Object> keyValueMap = jsonObject.getMap();
        for (Object value : keyValueMap.values()) {
            if (value instanceof Map) {
                return false;
            }
        }
        return true;
    }
}

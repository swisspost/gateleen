package org.swisspush.gateleen.core.util;

import io.vertx.core.MultiMap;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.json.JsonObject;

import java.util.Map;

/**
 * <p>
 * Utility class providing handy methods to deal with {@link JsonObject} objects.
 * </p>
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class JsonObjectUtils {

    private JsonObjectUtils() {
        // prevent instantiation
    }

    /**
     * Convert a {@link MultiMap} to a {@link JsonObject}. Returns an empty JsonObject when <code>null</code> is
     * provided.
     *
     * @param map the MultiMap to convert to a JsonObject
     * @return a JsonObject containing the entries from the MultiMap
     */
    public static JsonObject multiMapToJsonObject(MultiMap map) {
        JsonObject mapJsonObject = new JsonObject();
        if (map == null) {
            return mapJsonObject;
        }
        for (Map.Entry<String, String> entry : map) {
            mapJsonObject.put(entry.getKey(), entry.getValue());
        }
        return mapJsonObject;
    }

    /**
     * Convert a {@link JsonObject} to a {@link MultiMap}. Returns an empty MultiMap when <code>null</code> is
     * provided.
     *
     * @param jsonObject the JsonObject to convert to a Multimap
     * @return a MultiMap containing the entries from the JsonObject
     */
    public static MultiMap jsonObjectToMultiMap(JsonObject jsonObject) {
        CaseInsensitiveHeaders map = new CaseInsensitiveHeaders();
        if(jsonObject == null){
            return map;
        }
        for (Map.Entry<String, Object> stringObjectEntry : jsonObject.getMap().entrySet()) {
            map.add(stringObjectEntry.getKey(), String.valueOf(stringObjectEntry.getValue()));
        }
        return map;
    }
}

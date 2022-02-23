package org.swisspush.gateleen.core.util;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.*;
import java.util.stream.Stream;

/**
 * Useful utilities for Redis
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public final class RedisUtils {
    public static final String REPLY_STATUS = "status";
    public static final String REPLY_MESSAGE = "message";
    public static final String REPLY_VALUE = "value";
    public static final String STATUS_OK = "ok";

    private RedisUtils() {}

    /**
     * from https://github.com/vert-x3/vertx-redis-client/blob/3.9/src/main/java/io/vertx/redis/impl/RedisClientImpl.java#L94
     *
     * @param parameters
     * @return
     */
    public static List<String> toPayload(Object... parameters) {
        List<String> result = new ArrayList<>(parameters.length);

        for (Object param : parameters) {
            // unwrap
            if (param instanceof JsonArray) {
                param = ((JsonArray) param).getList();
            }
            // unwrap
            if (param instanceof JsonObject) {
                param = ((JsonObject) param).getMap();
            }

            if (param instanceof Collection) {
                ((Collection) param).stream().filter(Objects::nonNull).forEach(o -> result.add(o.toString()));
            } else if (param instanceof Map) {
                for (Map.Entry<?, ?> pair : ((Map<?, ?>) param).entrySet()) {
                    result.add(pair.getKey().toString());
                    result.add(pair.getValue().toString());
                }
            } else if (param instanceof Stream) {
                ((Stream) param).forEach(e -> {
                    if (e instanceof Object[]) {
                        Collections.addAll(result, (String[]) e);
                    } else {
                        result.add(e.toString());
                    }
                });
            } else if (param != null) {
                result.add(param.toString());
            }
        }
        return result;
    }

}

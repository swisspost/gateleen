package org.swisspush.gateleen.monitoring;

import io.vertx.core.Vertx;
import io.vertx.redis.client.RedisAPI;

/**
 * @deprecated Extend the {@link RedisMonitor} in your client source code
 * @author https://github.com/lbovet [Laurent Bovet] 19.09.2014
 */
public class CustomRedisMonitor extends RedisMonitor {

    public CustomRedisMonitor(Vertx vertx, RedisAPI redisAPI, String name, String restStoragePrefix, int period) {
        super(vertx, redisAPI, name, period);
        enableElementCount("expirable", restStoragePrefix + ":expirable");
    }
}

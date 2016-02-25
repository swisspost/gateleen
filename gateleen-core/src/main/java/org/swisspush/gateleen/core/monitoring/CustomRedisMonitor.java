package org.swisspush.gateleen.core.monitoring;

import io.vertx.core.Vertx;
import io.vertx.redis.RedisClient;

/**
 * Created by bovetl on 19.09.2014.
 */
public class CustomRedisMonitor extends RedisMonitor {

    public CustomRedisMonitor(Vertx vertx, RedisClient redisClient, String name, String restStoragePrefix, int period) {
        super(vertx, redisClient, name, period);
        enableElementCount("expirable", restStoragePrefix + ":expirable");
    }
}

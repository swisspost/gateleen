package org.swisspush.gateleen.monitoring;

import io.vertx.core.Vertx;
import io.vertx.redis.RedisClient;

/**
 * @author https://github.com/lbovet [Laurent Bovet] 19.09.2014
 */
public class CustomRedisMonitor extends RedisMonitor {

    public CustomRedisMonitor(Vertx vertx, RedisClient redisClient, String name, String restStoragePrefix, int period) {
        super(vertx, redisClient, name, period);
        enableElementCount("expirable", restStoragePrefix + ":expirable");
    }
}

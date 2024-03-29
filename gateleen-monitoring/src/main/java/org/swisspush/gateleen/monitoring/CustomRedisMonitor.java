package org.swisspush.gateleen.monitoring;

import io.vertx.core.Vertx;
import org.swisspush.gateleen.core.redis.RedisProvider;

/**
 * @deprecated Extend the {@link RedisMonitor} in your client source code
 * @author https://github.com/lbovet [Laurent Bovet] 19.09.2014
 */
public class CustomRedisMonitor extends RedisMonitor {

    public CustomRedisMonitor(Vertx vertx, RedisProvider redisProvider, String name, String restStoragePrefix, int period) {
        super(vertx, redisProvider, name, period);
        enableElementCount("expirable", restStoragePrefix + ":expirable");
    }
}

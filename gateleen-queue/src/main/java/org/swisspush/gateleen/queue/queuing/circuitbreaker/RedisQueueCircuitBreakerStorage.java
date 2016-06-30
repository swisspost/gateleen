package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.redis.RedisClient;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class RedisQueueCircuitBreakerStorage implements QueueCircuitBreakerStorage {

    private RedisClient redisClient;

    private String prefix = "gateleen.queue-circuit-breaker:";

    public RedisQueueCircuitBreakerStorage(RedisClient redisClient) {
        this.redisClient = redisClient;
    }

    @Override
    public void resetAllEndpoints() {
        redisClient.exists(key("abc"), event -> {

        });
    }

    private String key(String key){
        return prefix + key;
    }
}

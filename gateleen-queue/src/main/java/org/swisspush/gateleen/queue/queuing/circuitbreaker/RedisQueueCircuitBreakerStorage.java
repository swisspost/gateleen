package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.Future;
import io.vertx.redis.RedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.HashCodeGenerator;
import org.swisspush.gateleen.core.util.StringUtils;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class RedisQueueCircuitBreakerStorage implements QueueCircuitBreakerStorage {

    private RedisClient redisClient;
    private Logger log = LoggerFactory.getLogger(RedisQueueCircuitBreakerStorage.class);

    public static final String STORAGE_PREFIX = "gateleen.queue-circuit-breaker:";
    public static final String STORAGE_STATS_SUFFIX = ":stats";

    public static final String FIELD_STATUS = "status";

    public RedisQueueCircuitBreakerStorage(RedisClient redisClient) {
        this.redisClient = redisClient;
    }

    @Override
    public Future<Void> resetAllEndpoints() {
        Future<Void> future = Future.future();
        redisClient.exists(key("abc"), event -> {

        });
        return future;
    }

    @Override
    public Future<QueueCircuitState> getQueueCircuitState(String endpoint) {
        Future<QueueCircuitState> future = Future.future();
        String endpointHash = getHash(endpoint);
        redisClient.hget(key(endpointHash + STORAGE_STATS_SUFFIX), FIELD_STATUS, event -> {
            if(event.failed()){
                future.fail(event.cause());
            } else {
                String stateAsSTring = event.result();
                if(StringUtils.isEmpty(stateAsSTring)){
                    log.info("No status information found for endpoint " + endpoint + ". Using default value " + QueueCircuitState.CLOSED);
                }
                future.complete(QueueCircuitState.fromString(stateAsSTring, QueueCircuitState.CLOSED));
            }
        });
        return future;
    }

    private String key(String key){
        return STORAGE_PREFIX + key;
    }

    private String getHash(String input){ return HashCodeGenerator.createHashCode(input); }
}

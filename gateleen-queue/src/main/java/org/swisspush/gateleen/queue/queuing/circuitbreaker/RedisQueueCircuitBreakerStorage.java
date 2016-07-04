package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.Future;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.redis.RedisClient;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.core.util.HashCodeGenerator;
import org.swisspush.gateleen.core.util.StringUtils;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.lua.QueueCircuitBreakerLuaScripts;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.lua.UpdateQueueCircuitBreakerStatsRedisCommand;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class RedisQueueCircuitBreakerStorage implements QueueCircuitBreakerStorage {

    private RedisClient redisClient;
    private Logger log = LoggerFactory.getLogger(RedisQueueCircuitBreakerStorage.class);

    public static final String STORAGE_PREFIX = "gateleen.queue-circuit-breaker:";
    public static final String STORAGE_INFOS_SUFFIX = ":infos";
    public static final String FIELD_STATE = "state";

    private LuaScriptState updateCircuitBreakerLuaScriptState;

    public RedisQueueCircuitBreakerStorage(RedisClient redisClient) {
        this.redisClient = redisClient;

        updateCircuitBreakerLuaScriptState = new LuaScriptState(QueueCircuitBreakerLuaScripts.UPDATE_STATS, redisClient, false);
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
        redisClient.hget(key(endpointHash + STORAGE_INFOS_SUFFIX), FIELD_STATE, event -> {
            if(event.failed()){
                future.fail(event.cause());
            } else {
                String stateAsString = event.result();
                if(StringUtils.isEmpty(stateAsString)){
                    log.info("No status information found for endpoint " + endpoint + ". Using default value " + QueueCircuitState.CLOSED);
                }
                future.complete(QueueCircuitState.fromString(stateAsString, QueueCircuitState.CLOSED));
            }
        });
        return future;
    }

    @Override
    public Future<String> updateStatistics(String endpoint, String uniqueRequestID, long timestamp,
                                           long minSampleCount, long maxSampleCount, QueueResponseType queueResponseType) {
        Future<String> future = Future.future();
        List<String> keys = Collections.singletonList(statsKey(endpoint, queueResponseType));
        List<String> arguments = Arrays.asList(
                uniqueRequestID,
                String.valueOf(timestamp),
                String.valueOf(minSampleCount),
                String.valueOf(maxSampleCount)
        );
        UpdateQueueCircuitBreakerStatsRedisCommand cmd = new UpdateQueueCircuitBreakerStatsRedisCommand(updateCircuitBreakerLuaScriptState,
                keys, arguments, redisClient, log, future);
        cmd.exec(0);
        return future;
    }

    private String key(String key){
        return STORAGE_PREFIX + key;
    }

    private String statsKey(String endpoint, QueueResponseType queueResponseType){
        return STORAGE_PREFIX + getHash(endpoint) + queueResponseType.getKeySuffix();
    }

    private String getHash(String input){ return HashCodeGenerator.createHashCode(input); }
}

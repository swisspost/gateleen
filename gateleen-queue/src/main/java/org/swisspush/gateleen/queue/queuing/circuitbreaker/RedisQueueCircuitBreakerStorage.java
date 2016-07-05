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

    private LuaScriptState openCircuitLuaScriptState;

    public RedisQueueCircuitBreakerStorage(RedisClient redisClient) {
        this.redisClient = redisClient;

        openCircuitLuaScriptState = new LuaScriptState(QueueCircuitBreakerLuaScripts.OPEN_CIRCUIT, redisClient, false);
    }

    @Override
    public Future<Void> resetAllEndpoints() {
        Future<Void> future = Future.future();
        return future;
    }

    @Override
    public Future<QueueCircuitState> getQueueCircuitState(PatternAndEndpointHash patternAndEndpointHash) {
        Future<QueueCircuitState> future = Future.future();
        redisClient.hget(infosKey(patternAndEndpointHash.getEndpointHash()), FIELD_STATE, event -> {
            if(event.failed()){
                future.fail(event.cause());
            } else {
                String stateAsString = event.result();
                if(StringUtils.isEmpty(stateAsString)){
                    log.info("No status information found for endpoint " + patternAndEndpointHash.getPattern().pattern() + ". Using default value " + QueueCircuitState.CLOSED);
                }
                future.complete(QueueCircuitState.fromString(stateAsString, QueueCircuitState.CLOSED));
            }
        });
        return future;
    }

    @Override
    public Future<String> updateStatistics(PatternAndEndpointHash patternAndEndpointHash, String uniqueRequestID, long timestamp,
                                           int errorThresholdPercentage, long entriesMaxAgeMS, long minSampleCount,
                                           long maxSampleCount, QueueResponseType queueResponseType) {
        Future<String> future = Future.future();
        String endpointHash = patternAndEndpointHash.getEndpointHash();
        List<String> keys = Arrays.asList(
                infosKey(endpointHash),
                statsKey(endpointHash, QueueResponseType.SUCCESS),
                statsKey(endpointHash, QueueResponseType.FAILURE),
                statsKey(endpointHash, queueResponseType)
        );

        List<String> arguments = Arrays.asList(
                uniqueRequestID,
                String.valueOf(timestamp),
                String.valueOf(errorThresholdPercentage),
                String.valueOf(entriesMaxAgeMS),
                String.valueOf(minSampleCount),
                String.valueOf(maxSampleCount)
        );

        UpdateQueueCircuitBreakerStatsRedisCommand cmd = new UpdateQueueCircuitBreakerStatsRedisCommand(openCircuitLuaScriptState,
                keys, arguments, redisClient, log, future);
        cmd.exec(0);
        return future;
    }

    private String key(String key){
        return STORAGE_PREFIX + key;
    }

    private String infosKey(String endpointHash){
        return STORAGE_PREFIX + endpointHash + STORAGE_INFOS_SUFFIX;
    }

    private String statsKey(String endpointHash, QueueResponseType queueResponseType){
        return STORAGE_PREFIX + endpointHash + queueResponseType.getKeySuffix();
    }
}

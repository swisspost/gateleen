package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.Future;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.redis.RedisClient;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.core.util.StringUtils;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.lua.CloseCircuitRedisCommand;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.lua.QueueCircuitBreakerLuaScripts;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.lua.UpdateStatsRedisCommand;

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
    public static final String STORAGE_QUEUES_SUFFIX = ":queues";
    public static final String STORAGE_HALFOPEN_CIRCUITS = STORAGE_PREFIX + "half-open-circuits";
    public static final String STORAGE_QUEUES_TO_UNLOCK = STORAGE_PREFIX + "queues-to-unlock";
    public static final String FIELD_STATE = "state";
    public static final String FIELD_FAILRATIO = "failRatio";

    private LuaScriptState openCircuitLuaScriptState;
    private LuaScriptState closeCircuitLuaScriptState;

    public RedisQueueCircuitBreakerStorage(RedisClient redisClient) {
        this.redisClient = redisClient;

        openCircuitLuaScriptState = new LuaScriptState(QueueCircuitBreakerLuaScripts.UPDATE_CIRCUIT, redisClient, false);
        closeCircuitLuaScriptState = new LuaScriptState(QueueCircuitBreakerLuaScripts.CLOSE_CIRCUIT, redisClient, false);
    }

    @Override
    public Future<Void> resetAllEndpoints() {
        Future<Void> future = Future.future();
        return future;
    }

    @Override
    public Future<QueueCircuitState> getQueueCircuitState(PatternAndEndpointHash patternAndEndpointHash) {
        Future<QueueCircuitState> future = Future.future();
        redisClient.hget(buildInfosKey(patternAndEndpointHash.getEndpointHash()), FIELD_STATE, event -> {
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
    public Future<UpdateStatisticsResult> updateStatistics(PatternAndEndpointHash patternAndEndpointHash, String uniqueRequestID, long timestamp,
                                           int errorThresholdPercentage, long entriesMaxAgeMS, long minSampleCount,
                                           long maxSampleCount, QueueResponseType queueResponseType) {
        Future<UpdateStatisticsResult> future = Future.future();
        String endpointHash = patternAndEndpointHash.getEndpointHash();
        List<String> keys = Arrays.asList(
                buildInfosKey(endpointHash),
                buildStatsKey(endpointHash, QueueResponseType.SUCCESS),
                buildStatsKey(endpointHash, QueueResponseType.FAILURE),
                buildStatsKey(endpointHash, queueResponseType)
        );

        List<String> arguments = Arrays.asList(
                uniqueRequestID,
                patternAndEndpointHash.getPattern().pattern(),
                String.valueOf(timestamp),
                String.valueOf(errorThresholdPercentage),
                String.valueOf(entriesMaxAgeMS),
                String.valueOf(minSampleCount),
                String.valueOf(maxSampleCount)
        );

        UpdateStatsRedisCommand cmd = new UpdateStatsRedisCommand(openCircuitLuaScriptState,
                keys, arguments, redisClient, log, future);
        cmd.exec(0);
        return future;
    }

    @Override
    public Future<Void> lockQueue(String queueName, PatternAndEndpointHash patternAndEndpointHash) {
        Future<Void> future = Future.future();
        redisClient.zadd(buildQueuesKey(patternAndEndpointHash.getEndpointHash()), System.currentTimeMillis(), queueName, event -> {
            if(event.failed()){
                future.fail(event.cause().getMessage());
                return;
            }
            future.complete();
        });
        return future;
    }

    @Override
    public Future<Void> closeCircuit(PatternAndEndpointHash patternAndEndpointHash) {
        Future<Void> future = Future.future();
        String endpointHash = patternAndEndpointHash.getEndpointHash();

        List<String> keys = Arrays.asList(
                buildInfosKey(endpointHash),
                buildStatsKey(endpointHash, QueueResponseType.SUCCESS),
                buildStatsKey(endpointHash, QueueResponseType.FAILURE),
                buildQueuesKey(endpointHash),
                STORAGE_HALFOPEN_CIRCUITS,
                STORAGE_QUEUES_TO_UNLOCK
        );

        List<String> arguments = Collections.singletonList(patternAndEndpointHash.getEndpointHash());

        CloseCircuitRedisCommand cmd = new CloseCircuitRedisCommand(closeCircuitLuaScriptState,
                keys, arguments, redisClient, log, future);
        cmd.exec(0);
        return future;
    }

    /*
         * Helper methods
         */
    private String buildInfosKey(String endpointHash){
        return STORAGE_PREFIX + endpointHash + STORAGE_INFOS_SUFFIX;
    }

    private String buildQueuesKey(String endpointHash){
        return STORAGE_PREFIX + endpointHash + STORAGE_QUEUES_SUFFIX;
    }

    private String buildStatsKey(String endpointHash, QueueResponseType queueResponseType){
        return STORAGE_PREFIX + endpointHash + queueResponseType.getKeySuffix();
    }
}

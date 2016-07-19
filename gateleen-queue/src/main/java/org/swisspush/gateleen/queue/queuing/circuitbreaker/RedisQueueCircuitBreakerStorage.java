package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.redis.RedisClient;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.core.util.StringUtils;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.lua.*;

import java.util.ArrayList;
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
    public static final String STORAGE_OPEN_CIRCUITS = STORAGE_PREFIX + "open-circuits";
    public static final String STORAGE_QUEUES_TO_UNLOCK = STORAGE_PREFIX + "queues-to-unlock";
    public static final String FIELD_STATE = "state";
    public static final String FIELD_FAILRATIO = "failRatio";
    public static final String FIELD_CIRCUIT = "circuit";

    private LuaScriptState openCircuitLuaScriptState;
    private LuaScriptState closeCircuitLuaScriptState;
    private LuaScriptState reOpenCircuitLuaScriptState;
    private LuaScriptState halfOpenCircuitLuaScriptState;
    private LuaScriptState unlockSampleQueuesLuaScriptState;

    public RedisQueueCircuitBreakerStorage(RedisClient redisClient) {
        this.redisClient = redisClient;

        openCircuitLuaScriptState = new LuaScriptState(QueueCircuitBreakerLuaScripts.UPDATE_CIRCUIT, redisClient, false);
        closeCircuitLuaScriptState = new LuaScriptState(QueueCircuitBreakerLuaScripts.CLOSE_CIRCUIT, redisClient, false);
        reOpenCircuitLuaScriptState = new LuaScriptState(QueueCircuitBreakerLuaScripts.REOPEN_CIRCUIT, redisClient, false);
        halfOpenCircuitLuaScriptState = new LuaScriptState(QueueCircuitBreakerLuaScripts.HALFOPEN_CIRCUITS, redisClient, false);
        unlockSampleQueuesLuaScriptState = new LuaScriptState(QueueCircuitBreakerLuaScripts.UNLOCK_SAMPLES, redisClient, false);
    }

    @Override
    public Future<QueueCircuitState> getQueueCircuitState(PatternAndCircuitHash patternAndCircuitHash) {
        Future<QueueCircuitState> future = Future.future();
        redisClient.hget(buildInfosKey(patternAndCircuitHash.getCircuitHash()), FIELD_STATE, event -> {
            if(event.failed()){
                future.fail(event.cause());
            } else {
                String stateAsString = event.result();
                if(StringUtils.isEmpty(stateAsString)){
                    log.info("No status information found for circuit " + patternAndCircuitHash.getPattern().pattern() + ". Using default value " + QueueCircuitState.CLOSED);
                }
                future.complete(QueueCircuitState.fromString(stateAsString, QueueCircuitState.CLOSED));
            }
        });
        return future;
    }

    @Override
    public Future<QueueCircuitState> getQueueCircuitState(String circuitHash) {
        Future<QueueCircuitState> future = Future.future();
        redisClient.hget(buildInfosKey(circuitHash), FIELD_STATE, event -> {
            if(event.failed()){
                future.fail(event.cause());
            } else {
                String stateAsString = event.result();
                if(StringUtils.isEmpty(stateAsString)){
                    log.info("No status information found for circuit " + circuitHash + ". Using default value " + QueueCircuitState.CLOSED);
                }
                future.complete(QueueCircuitState.fromString(stateAsString, QueueCircuitState.CLOSED));
            }
        });
        return future;
    }

    @Override
    public Future<JsonObject> getQueueCircuitInformation(String circuitHash) {
        Future<JsonObject> future = Future.future();
        List<String> fields = Arrays.asList(FIELD_STATE, FIELD_FAILRATIO, FIELD_CIRCUIT);
        redisClient.hmget(buildInfosKey(circuitHash), fields, event -> {
            if(event.failed()){
                future.fail(event.cause());
            } else {
                try {
                    QueueCircuitState state = QueueCircuitState.fromString(event.result().getString(0), QueueCircuitState.CLOSED);
                    String failRatioStr = event.result().getString(1);
                    String circuit = event.result().getString(2);
                    JsonObject result = new JsonObject();
                    result.put("status", state.name());
                    JsonObject info = new JsonObject();
                    if (failRatioStr != null) {
                        info.put(FIELD_FAILRATIO, Integer.valueOf(failRatioStr));
                    }
                    if (circuit != null) {
                        info.put(FIELD_CIRCUIT, circuit);
                    }
                    result.put("info", info);
                    future.complete(result);
                }catch (Exception e){
                    future.fail(e);
                }
            }
        });
        return future;
    }

    @Override
    public Future<UpdateStatisticsResult> updateStatistics(PatternAndCircuitHash patternAndCircuitHash, String uniqueRequestID, long timestamp,
                                                           int errorThresholdPercentage, long entriesMaxAgeMS, long minQueueSampleCount,
                                                           long maxQueueSampleCount, QueueResponseType queueResponseType) {
        Future<UpdateStatisticsResult> future = Future.future();
        String circuitHash = patternAndCircuitHash.getCircuitHash();
        List<String> keys = Arrays.asList(
                buildInfosKey(circuitHash),
                buildStatsKey(circuitHash, QueueResponseType.SUCCESS),
                buildStatsKey(circuitHash, QueueResponseType.FAILURE),
                buildStatsKey(circuitHash, queueResponseType),
                STORAGE_OPEN_CIRCUITS
        );

        List<String> arguments = Arrays.asList(
                uniqueRequestID,
                patternAndCircuitHash.getPattern().pattern(),
                patternAndCircuitHash.getCircuitHash(),
                String.valueOf(timestamp),
                String.valueOf(errorThresholdPercentage),
                String.valueOf(entriesMaxAgeMS),
                String.valueOf(minQueueSampleCount),
                String.valueOf(maxQueueSampleCount)
        );

        UpdateStatsRedisCommand cmd = new UpdateStatsRedisCommand(openCircuitLuaScriptState,
                keys, arguments, redisClient, log, future);
        cmd.exec(0);
        return future;
    }

    @Override
    public Future<Void> lockQueue(String queueName, PatternAndCircuitHash patternAndCircuitHash) {
        Future<Void> future = Future.future();
        redisClient.zadd(buildQueuesKey(patternAndCircuitHash.getCircuitHash()), System.currentTimeMillis(), queueName, event -> {
            if(event.failed()){
                future.fail(event.cause().getMessage());
                return;
            }
            future.complete();
        });
        return future;
    }

    @Override
    public Future<String> popQueueToUnlock() {
        Future<String> future = Future.future();
        redisClient.lpop(STORAGE_QUEUES_TO_UNLOCK, event -> {
            if(event.failed()){
                future.fail(event.cause().getMessage());
                return;
            }
            future.complete(event.result());
        });
        return future;
    }

    @Override
    public Future<Void> closeCircuit(PatternAndCircuitHash patternAndCircuitHash) {
        return closeCircuit(patternAndCircuitHash.getCircuitHash(), false);
    }

    @Override
    public Future<Void> closeAndRemoveCircuit(PatternAndCircuitHash patternAndCircuitHash) {
        return closeCircuit(patternAndCircuitHash.getCircuitHash(), true);
    }

    private Future<Void> closeCircuit(String circuitHash, boolean circuitRemoved){
        Future<Void> future = Future.future();

        List<String> keys = Arrays.asList(
                buildInfosKey(circuitHash),
                buildStatsKey(circuitHash, QueueResponseType.SUCCESS),
                buildStatsKey(circuitHash, QueueResponseType.FAILURE),
                buildQueuesKey(circuitHash),
                STORAGE_HALFOPEN_CIRCUITS,
                STORAGE_OPEN_CIRCUITS,
                STORAGE_QUEUES_TO_UNLOCK
        );

        List<String> arguments = Arrays.asList(
                circuitHash,
                String.valueOf(circuitRemoved)
        );

        CloseCircuitRedisCommand cmd = new CloseCircuitRedisCommand(closeCircuitLuaScriptState,
                keys, arguments, redisClient, log, future);
        cmd.exec(0);
        return future;
    }

    @Override
    public Future<Void> closeAllCircuits() {
        Future<Void> future = Future.future();

        Future<Void> closeOpenCircuitsFuture = closeCircuitsByKey(STORAGE_OPEN_CIRCUITS);
        Future<Void> closeHalfOpenCircuitsFuture = closeCircuitsByKey(STORAGE_HALFOPEN_CIRCUITS);

        CompositeFuture.all(closeOpenCircuitsFuture, closeHalfOpenCircuitsFuture).setHandler(event -> {
            if(event.succeeded()){
                future.complete();
            } else {
                future.fail(event.cause().getMessage());
            }
        });

        return future;
    }

    private Future<Void> closeCircuitsByKey(String key) {
        Future<Void> future = Future.future();
        redisClient.smembers(key, event -> {
            if(event.succeeded()){
                List<Future> futures = new ArrayList<>();
                List<Object> openCircuits = event.result().getList();
                for (Object circuit : openCircuits) {
                    futures.add(closeCircuit((String)circuit, false));
                }
                CompositeFuture.all(futures).setHandler(event1 -> {
                    if(event1.succeeded()){
                        future.complete();
                    } else {
                        future.fail(event1.cause().getMessage());
                    }
                });
            } else {
                future.fail(event.cause().getMessage());
            }
        });
        return future;
    }

    @Override
    public Future<Void> reOpenCircuit(PatternAndCircuitHash patternAndCircuitHash) {
        Future<Void> future = Future.future();
        String circuitHash = patternAndCircuitHash.getCircuitHash();

        List<String> keys = Arrays.asList(
                buildInfosKey(circuitHash),
                STORAGE_HALFOPEN_CIRCUITS,
                STORAGE_OPEN_CIRCUITS
        );

        List<String> arguments = Collections.singletonList(circuitHash);

        ReOpenCircuitRedisCommand cmd = new ReOpenCircuitRedisCommand(reOpenCircuitLuaScriptState,
                keys, arguments, redisClient, log, future);
        cmd.exec(0);

        return future;
    }

    @Override
    public Future<Long> setOpenCircuitsToHalfOpen() {
        Future<Long> future = Future.future();
        List<String> keys = Arrays.asList(STORAGE_HALFOPEN_CIRCUITS, STORAGE_OPEN_CIRCUITS);
        List<String> arguments = Arrays.asList(STORAGE_PREFIX, STORAGE_INFOS_SUFFIX);
        HalfOpenCircuitRedisCommand cmd = new HalfOpenCircuitRedisCommand(halfOpenCircuitLuaScriptState,
                keys, arguments, redisClient, log, future);
        cmd.exec(0);
        return future;
    }

    @Override
    public Future<List<String>> unlockSampleQueues() {
        Future<List<String>> future = Future.future();

        List<String> keys = Collections.singletonList(STORAGE_HALFOPEN_CIRCUITS);

        List<String> arguments = Arrays.asList(
                STORAGE_PREFIX,
                STORAGE_QUEUES_SUFFIX,
                String.valueOf(System.currentTimeMillis()));

        UnlockSampleQueuesRedisCommand cmd = new UnlockSampleQueuesRedisCommand(unlockSampleQueuesLuaScriptState,
                keys, arguments, redisClient, log, future);
        cmd.exec(0);

        return future;
    }

    /*
     * Helper methods
     */
    private String buildInfosKey(String circuitHash){
        return STORAGE_PREFIX + circuitHash + STORAGE_INFOS_SUFFIX;
    }

    private String buildQueuesKey(String circuitHash){
        return STORAGE_PREFIX + circuitHash + STORAGE_QUEUES_SUFFIX;
    }

    private String buildStatsKey(String circuitHash, QueueResponseType queueResponseType){
        return STORAGE_PREFIX + circuitHash + queueResponseType.getKeySuffix();
    }
}

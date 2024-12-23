package org.swisspush.gateleen.queue.queuing.circuitbreaker.impl;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.core.redis.RedisProvider;
import org.swisspush.gateleen.core.util.StringUtils;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.QueueCircuitBreakerStorage;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.lua.*;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.PatternAndCircuitHash;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitState;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueResponseType;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.UpdateStatisticsResult;

import java.util.*;

/**
 * Redis based implementation of the {@link QueueCircuitBreakerStorage} interface.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class RedisQueueCircuitBreakerStorage implements QueueCircuitBreakerStorage {

    private final RedisProvider redisProvider;
    private final Logger log = LoggerFactory.getLogger(RedisQueueCircuitBreakerStorage.class);

    public static final String STORAGE_PREFIX = "gateleen.queue-circuit-breaker:";
    public static final String STORAGE_INFOS_SUFFIX = ":infos";
    public static final String STORAGE_QUEUES_SUFFIX = ":queues";
    public static final String STORAGE_ALL_CIRCUITS = STORAGE_PREFIX + "all-circuits";
    public static final String STORAGE_HALFOPEN_CIRCUITS = STORAGE_PREFIX + "half-open-circuits";
    public static final String STORAGE_OPEN_CIRCUITS = STORAGE_PREFIX + "open-circuits";
    public static final String STORAGE_QUEUES_TO_UNLOCK = STORAGE_PREFIX + "queues-to-unlock";
    public static final String FIELD_STATE = "state";
    public static final String FIELD_STATUS = "status";
    public static final String FIELD_FAILRATIO = "failRatio";
    public static final String FIELD_CIRCUIT = "circuit";
    public static final String FIELD_METRICNAME = "metricName";

    private final LuaScriptState openCircuitLuaScriptState;
    private final LuaScriptState closeCircuitLuaScriptState;
    private final LuaScriptState reOpenCircuitLuaScriptState;
    private final LuaScriptState halfOpenCircuitLuaScriptState;
    private final LuaScriptState unlockSampleQueuesLuaScriptState;
    private final LuaScriptState getAllCircuitsLuaScriptState;

    public RedisQueueCircuitBreakerStorage(RedisProvider redisProvider, GateleenExceptionFactory exceptionFactory) {
        this.redisProvider = redisProvider;

        openCircuitLuaScriptState = new LuaScriptState(QueueCircuitBreakerLuaScripts.UPDATE_CIRCUIT, redisProvider, exceptionFactory, false);
        closeCircuitLuaScriptState = new LuaScriptState(QueueCircuitBreakerLuaScripts.CLOSE_CIRCUIT, redisProvider, exceptionFactory, false);
        reOpenCircuitLuaScriptState = new LuaScriptState(QueueCircuitBreakerLuaScripts.REOPEN_CIRCUIT, redisProvider, exceptionFactory, false);
        halfOpenCircuitLuaScriptState = new LuaScriptState(QueueCircuitBreakerLuaScripts.HALFOPEN_CIRCUITS, redisProvider, exceptionFactory, false);
        unlockSampleQueuesLuaScriptState = new LuaScriptState(QueueCircuitBreakerLuaScripts.UNLOCK_SAMPLES, redisProvider, exceptionFactory, false);
        getAllCircuitsLuaScriptState = new LuaScriptState(QueueCircuitBreakerLuaScripts.ALL_CIRCUITS, redisProvider, exceptionFactory, false);
    }

    @Override
    public Future<QueueCircuitState> getQueueCircuitState(PatternAndCircuitHash patternAndCircuitHash) {
        Promise<QueueCircuitState> promise = Promise.promise();
        redisProvider.redis().onSuccess(redisAPI -> redisAPI.hget(buildInfosKey(patternAndCircuitHash.getCircuitHash()),
                FIELD_STATE, event -> {
                    if (event.failed()) {
                        promise.fail(event.cause());
                    } else {
                        String stateAsString = Objects.toString(event.result(), "");
                        if (StringUtils.isEmpty(stateAsString)) {
                            log.info("No status information found for circuit {}. Using default value {}",
                                    patternAndCircuitHash.getPattern().pattern(), QueueCircuitState.CLOSED);
                        }
                        promise.complete(QueueCircuitState.fromString(stateAsString, QueueCircuitState.CLOSED));
                    }
                })).onFailure(promise::fail);
        return promise.future();
    }

    @Override
    public Future<QueueCircuitState> getQueueCircuitState(String circuitHash) {
        Promise<QueueCircuitState> promise = Promise.promise();
        redisProvider.redis().onSuccess(redisAPI -> redisAPI.hget(buildInfosKey(circuitHash), FIELD_STATE, event -> {
            if (event.failed()) {
                promise.fail(event.cause());
            } else {
                String stateAsString = Objects.toString(event.result(), "");
                if (StringUtils.isEmpty(stateAsString)) {
                    log.info("No status information found for circuit {}. Using default value {}", circuitHash, QueueCircuitState.CLOSED);
                }
                promise.complete(QueueCircuitState.fromString(stateAsString, QueueCircuitState.CLOSED));
            }
        })).onFailure(promise::fail);
        return promise.future();
    }

    @Override
    public Future<JsonObject> getQueueCircuitInformation(String circuitHash) {
        Promise<JsonObject> promise = Promise.promise();
        redisProvider.redis().onSuccess(redisAPI -> redisAPI.hmget(Arrays.asList(buildInfosKey(circuitHash), FIELD_STATE,
                FIELD_FAILRATIO, FIELD_CIRCUIT, FIELD_METRICNAME), event -> {
            if (event.failed()) {
                promise.fail(event.cause());
            } else {
                try {
                    QueueCircuitState state = QueueCircuitState.fromString(Objects.toString(event.result().get(0), null),
                            QueueCircuitState.CLOSED);
                    String failRatioStr = Objects.toString(event.result().get(1), null);
                    String circuit = Objects.toString(event.result().get(2), null);
                    String metric = Objects.toString(event.result().get(3), null);
                    JsonObject result = new JsonObject();
                    result.put(FIELD_STATUS, state.name().toLowerCase());
                    JsonObject info = new JsonObject();
                    if (failRatioStr != null) {
                        info.put(FIELD_FAILRATIO, Integer.valueOf(failRatioStr));
                    }
                    if (circuit != null) {
                        info.put(FIELD_CIRCUIT, circuit);
                    }
                    if (StringUtils.isNotEmptyTrimmed(metric)) {
                        info.put(FIELD_METRICNAME, metric);
                    }
                    result.put("info", info);
                    promise.complete(result);
                } catch (Exception e) {
                    promise.fail(e);
                }
            }
        })).onFailure(promise::fail);
        return promise.future();
    }

    @Override
    public Future<JsonObject> getAllCircuits() {
        Promise<JsonObject> promise = Promise.promise();
        List<String> keys = Collections.singletonList(STORAGE_ALL_CIRCUITS);
        List<String> arguments = Arrays.asList(STORAGE_PREFIX, STORAGE_INFOS_SUFFIX);
        GetAllCircuitsRedisCommand cmd = new GetAllCircuitsRedisCommand(getAllCircuitsLuaScriptState,
                keys, arguments, redisProvider, log, promise);
        cmd.exec(0);
        return promise.future();
    }

    @Override
    public Future<UpdateStatisticsResult> updateStatistics(PatternAndCircuitHash patternAndCircuitHash, String uniqueRequestID, long timestamp,
                                                           int errorThresholdPercentage, long entriesMaxAgeMS, long minQueueSampleCount,
                                                           long maxQueueSampleCount, QueueResponseType queueResponseType) {
        Promise<UpdateStatisticsResult> promise = Promise.promise();
        String circuitHash = patternAndCircuitHash.getCircuitHash();
        List<String> keys = Arrays.asList(
                buildInfosKey(circuitHash),
                buildStatsKey(circuitHash, QueueResponseType.SUCCESS),
                buildStatsKey(circuitHash, QueueResponseType.FAILURE),
                buildStatsKey(circuitHash, queueResponseType),
                STORAGE_OPEN_CIRCUITS,
                STORAGE_ALL_CIRCUITS
        );

        List<String> arguments = Arrays.asList(
                uniqueRequestID,
                patternAndCircuitHash.getPattern().pattern(),
                patternAndCircuitHash.getMetricName() != null ? patternAndCircuitHash.getMetricName() : "",
                patternAndCircuitHash.getCircuitHash(),
                String.valueOf(timestamp),
                String.valueOf(errorThresholdPercentage),
                String.valueOf(entriesMaxAgeMS),
                String.valueOf(minQueueSampleCount),
                String.valueOf(maxQueueSampleCount)
        );

        UpdateStatsRedisCommand cmd = new UpdateStatsRedisCommand(openCircuitLuaScriptState,
                keys, arguments, redisProvider, log, promise);
        cmd.exec(0);
        return promise.future();
    }

    @Override
    public Future<Void> lockQueue(String queueName, PatternAndCircuitHash patternAndCircuitHash) {
        Promise<Void> promise = Promise.promise();
        redisProvider.redis().onSuccess(redisAPI -> redisAPI.zadd(Arrays.asList(buildQueuesKey(patternAndCircuitHash.getCircuitHash()),
                String.valueOf(System.currentTimeMillis()), queueName), event -> {
            if (event.failed()) {
                promise.fail(event.cause().getMessage());
                return;
            }
            promise.complete();
        })).onFailure(throwable -> promise.fail(throwable.getMessage()));
        return promise.future();
    }

    @Override
    public Future<String> popQueueToUnlock() {
        Promise<String> promise = Promise.promise();
        redisProvider.redis().onSuccess(redisAPI -> redisAPI.lpop(Collections.singletonList(STORAGE_QUEUES_TO_UNLOCK),
                event -> {
                    if (event.failed()) {
                        promise.fail(event.cause().getMessage());
                        return;
                    }
                    promise.complete(Objects.toString(event.result(), null));
                })).onFailure(throwable -> promise.fail(throwable.getMessage()));
        return promise.future();
    }

    @Override
    public Future<Void> closeCircuit(PatternAndCircuitHash patternAndCircuitHash) {
        return closeCircuit(patternAndCircuitHash.getCircuitHash(), false);
    }

    @Override
    public Future<Void> closeAndRemoveCircuit(PatternAndCircuitHash patternAndCircuitHash) {
        return closeCircuit(patternAndCircuitHash.getCircuitHash(), true);
    }

    private Future<Void> closeCircuit(String circuitHash, boolean circuitRemoved) {
        Promise<Void> promise = Promise.promise();

        List<String> keys = Arrays.asList(
                buildInfosKey(circuitHash),
                buildStatsKey(circuitHash, QueueResponseType.SUCCESS),
                buildStatsKey(circuitHash, QueueResponseType.FAILURE),
                buildQueuesKey(circuitHash),
                STORAGE_ALL_CIRCUITS,
                STORAGE_HALFOPEN_CIRCUITS,
                STORAGE_OPEN_CIRCUITS,
                STORAGE_QUEUES_TO_UNLOCK
        );

        List<String> arguments = Arrays.asList(
                circuitHash,
                String.valueOf(circuitRemoved)
        );

        CloseCircuitRedisCommand cmd = new CloseCircuitRedisCommand(closeCircuitLuaScriptState,
                keys, arguments, redisProvider, log, promise);
        cmd.exec(0);
        return promise.future();
    }

    @Override
    public Future<Void> closeAllCircuits() {
        Promise<Void> promise = Promise.promise();

        Future<Void> closeOpenCircuitsFuture = closeCircuitsByKey(STORAGE_OPEN_CIRCUITS);
        Future<Void> closeHalfOpenCircuitsFuture = closeCircuitsByKey(STORAGE_HALFOPEN_CIRCUITS);

        CompositeFuture.all(closeOpenCircuitsFuture, closeHalfOpenCircuitsFuture).onComplete(event -> {
            if (event.succeeded()) {
                promise.complete();
            } else {
                promise.fail(event.cause().getMessage());
            }
        });

        return promise.future();
    }

    private Future<Void> closeCircuitsByKey(String key) {
        Promise<Void> promise = Promise.promise();
        redisProvider.redis().onSuccess(redisAPI -> redisAPI.smembers(key, event -> {
            if (event.succeeded()) {
                List<Future> promises = new ArrayList<>();
                for (Response circuit : event.result()) {
                    promises.add(closeCircuit(circuit.toString(), false));
                }
                if (promises.size() == 0) {
                    promise.complete();
                } else {
                    CompositeFuture.all(promises).onComplete(event1 -> {
                        if (event1.succeeded()) {
                            promise.complete();
                        } else {
                            promise.fail(event1.cause().getMessage());
                        }
                    });
                }
            } else {
                promise.fail(event.cause().getMessage());
            }
        })).onFailure(throwable -> promise.fail(throwable.getMessage()));
        return promise.future();
    }

    @Override
    public Future<Void> reOpenCircuit(PatternAndCircuitHash patternAndCircuitHash) {
        Promise<Void> promise = Promise.promise();
        String circuitHash = patternAndCircuitHash.getCircuitHash();

        List<String> keys = Arrays.asList(
                buildInfosKey(circuitHash),
                STORAGE_HALFOPEN_CIRCUITS,
                STORAGE_OPEN_CIRCUITS
        );

        List<String> arguments = Collections.singletonList(circuitHash);

        ReOpenCircuitRedisCommand cmd = new ReOpenCircuitRedisCommand(reOpenCircuitLuaScriptState,
                keys, arguments, redisProvider, log, promise);
        cmd.exec(0);

        return promise.future();
    }

    @Override
    public Future<Long> setOpenCircuitsToHalfOpen() {
        Promise<Long> promise = Promise.promise();
        List<String> keys = Arrays.asList(STORAGE_HALFOPEN_CIRCUITS, STORAGE_OPEN_CIRCUITS);
        List<String> arguments = Arrays.asList(STORAGE_PREFIX, STORAGE_INFOS_SUFFIX);
        HalfOpenCircuitRedisCommand cmd = new HalfOpenCircuitRedisCommand(halfOpenCircuitLuaScriptState,
                keys, arguments, redisProvider, log, promise);
        cmd.exec(0);
        return promise.future();
    }

    @Override
    public Future<Response> unlockSampleQueues() {
        Promise<Response> promise = Promise.promise();

        List<String> keys = Collections.singletonList(STORAGE_HALFOPEN_CIRCUITS);

        List<String> arguments = Arrays.asList(
                STORAGE_PREFIX,
                STORAGE_QUEUES_SUFFIX,
                String.valueOf(System.currentTimeMillis()));

        UnlockSampleQueuesRedisCommand cmd = new UnlockSampleQueuesRedisCommand(unlockSampleQueuesLuaScriptState,
                keys, arguments, redisProvider, log, promise);
        cmd.exec(0);

        return promise.future();
    }

    /*
     * Helper methods
     */
    private String buildInfosKey(String circuitHash) {
        return STORAGE_PREFIX + circuitHash + STORAGE_INFOS_SUFFIX;
    }

    private String buildQueuesKey(String circuitHash) {
        return STORAGE_PREFIX + circuitHash + STORAGE_QUEUES_SUFFIX;
    }

    private String buildStatsKey(String circuitHash, QueueResponseType queueResponseType) {
        return STORAGE_PREFIX + circuitHash + queueResponseType.getKeySuffix();
    }
}

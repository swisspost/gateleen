package org.swisspush.gateleen.hook.reducedpropagation.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.core.redis.RedisProvider;
import org.swisspush.gateleen.core.util.StringUtils;
import org.swisspush.gateleen.hook.reducedpropagation.ReducedPropagationStorage;
import org.swisspush.gateleen.hook.reducedpropagation.lua.ReducedPropagationLuaScripts;
import org.swisspush.gateleen.hook.reducedpropagation.lua.RemoveExpiredQueuesRedisCommand;
import org.swisspush.gateleen.hook.reducedpropagation.lua.StartQueueTimerRedisCommand;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Redis based implementation of the {@link ReducedPropagationStorage} interface.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class RedisReducedPropagationStorage implements ReducedPropagationStorage {
    private RedisProvider redisProvider;
    private Logger log = LoggerFactory.getLogger(RedisReducedPropagationStorage.class);

    static final String QUEUE_TIMERS = "gateleen.hook-reducedpropagation-queuetimers";
    static final String QUEUE_REQUESTS = "gateleen.hook-reducedpropagation-queuerequests";

    private LuaScriptState startQueueTimerLuaScriptState;
    private LuaScriptState removeExpiredQueuesRedisCommand;

    public RedisReducedPropagationStorage(RedisProvider redisProvider, GateleenExceptionFactory exceptionFactory) {
        this.redisProvider = redisProvider;

        startQueueTimerLuaScriptState = new LuaScriptState(ReducedPropagationLuaScripts.START_QUEUE_TIMER, redisProvider, exceptionFactory, false);
        removeExpiredQueuesRedisCommand = new LuaScriptState(ReducedPropagationLuaScripts.REMOVE_EXPIRED_QUEUES, redisProvider, exceptionFactory, false);
    }

    @Override
    public Future<Response> removeExpiredQueues(long currentTS) {
        Promise<Response> promise = Promise.promise();
        List<String> keys = Collections.singletonList(QUEUE_TIMERS);
        List<String> arguments = Collections.singletonList(String.valueOf(currentTS));
        RemoveExpiredQueuesRedisCommand cmd = new RemoveExpiredQueuesRedisCommand(removeExpiredQueuesRedisCommand,
                keys, arguments, redisProvider, log, promise);
        cmd.exec(0);
        return promise.future();
    }

    @Override
    public Future<Boolean> addQueue(String queue, long expireTS) {
        Promise<Boolean> promise = Promise.promise();
        List<String> keys = Collections.singletonList(QUEUE_TIMERS);
        List<String> arguments = Arrays.asList(queue, String.valueOf(expireTS));
        StartQueueTimerRedisCommand cmd = new StartQueueTimerRedisCommand(startQueueTimerLuaScriptState,
                keys, arguments, redisProvider, log, promise);
        cmd.exec(0);
        return promise.future();
    }

    @Override
    public Future<Void> storeQueueRequest(String queue, JsonObject queueRequest) {
        Promise<Void> promise = Promise.promise();

        if (StringUtils.isEmpty(queue)) {
            promise.fail("Queue is not allowed to be empty");
            return promise.future();
        }
        if (queueRequest == null) {
            promise.fail("Request is not allowed to be empty");
            return promise.future();
        }

        try {
            String queueRequestStr = queueRequest.encode();
            redisProvider.redis().onSuccess(redisAPI -> redisAPI.hset(Arrays.asList(QUEUE_REQUESTS, queue, queueRequestStr), reply -> {
                if (reply.failed()) {
                    String message = "Failed to store request for queue '" + queue + "'. Cause: " + logCause(reply);
                    log.error(message);
                    promise.fail(message);
                } else {
                    promise.complete();
                }
            })).onFailure(throwable -> {
                String message = "Failed to store request for queue '" + queue + "'. Cause: " + throwable.getMessage();
                failPromise(promise, message, throwable);
            });
        } catch (DecodeException ex) {
            promise.fail("Failed to decode request for queue '" + queue + "'");
            return promise.future();
        }
        return promise.future();
    }

    @Override
    public Future<JsonObject> getQueueRequest(String queue) {
        Promise<JsonObject> promise = Promise.promise();

        if (StringUtils.isEmpty(queue)) {
            promise.fail("Queue is not allowed to be empty");
            return promise.future();
        }

        redisProvider.redis().onSuccess(redisAPI -> redisAPI.hget(QUEUE_REQUESTS, queue, reply -> {
            if (reply.failed()) {
                String message = "get queue request '" + queue + "' from hash '" + QUEUE_REQUESTS + "' resulted in cause " + logCause(reply);
                log.error(message);
                promise.fail(message);
            } else {
                String resultStr = Objects.toString(reply.result(), "");
                if (StringUtils.isNotEmpty(resultStr)) {
                    try {
                        JsonObject queueRequest = new JsonObject(resultStr);
                        promise.complete(queueRequest);
                    } catch (DecodeException ex) {
                        promise.fail("Failed to decode queue request for queue '" + queue + "'. Got this from storage: " + resultStr);
                    }
                } else {
                    promise.complete(null);
                }
            }
        })).onFailure(throwable -> {
            String message = "get queue request '" + queue + "' from hash '" + QUEUE_REQUESTS + "' resulted in cause " + throwable.getMessage();
            failPromise(promise, message, throwable);
        });

        return promise.future();
    }

    @Override
    public Future<Void> removeQueueRequest(String queue) {
        Promise<Void> promise = Promise.promise();

        if (StringUtils.isEmpty(queue)) {
            promise.fail("Queue is not allowed to be empty");
            return promise.future();
        }

        redisProvider.redis().onSuccess(redisAPI -> redisAPI.hdel(Arrays.asList(QUEUE_REQUESTS, queue), reply -> {
            if (reply.failed()) {
                String message = "Failed to remove request for queue '" + queue + "'. Cause: " + logCause(reply);
                log.error(message);
                promise.fail(message);
            } else {
                promise.complete();
            }
        })).onFailure(throwable -> {
            String message = "Failed to remove request for queue '" + queue + "'. Cause: " + throwable.getMessage();
            failPromise(promise, message, throwable);
        });

        return promise.future();
    }

    private static String logCause(AsyncResult result) {
        if (result.cause() != null) {
            return result.cause().getMessage();
        }
        return null;
    }

    private void failPromise(Promise<?> promise, String message, Throwable throwable) {
        log.error(message, throwable);
        promise.fail(message);
    }
}

package org.swisspush.gateleen.hook.reducedpropagation.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.redis.RedisClient;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.core.util.StringUtils;
import org.swisspush.gateleen.hook.reducedpropagation.ReducedPropagationStorage;
import org.swisspush.gateleen.hook.reducedpropagation.lua.ReducedPropagationLuaScripts;
import org.swisspush.gateleen.hook.reducedpropagation.lua.RemoveExpiredQueuesRedisCommand;
import org.swisspush.gateleen.hook.reducedpropagation.lua.StartQueueTimerRedisCommand;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Redis based implementation of the {@link ReducedPropagationStorage} interface.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class RedisReducedPropagationStorage implements ReducedPropagationStorage {
    private RedisClient redisClient;
    private Logger log = LoggerFactory.getLogger(RedisReducedPropagationStorage.class);

    static final String QUEUE_TIMERS = "gateleen.hook-reducedpropagation-queuetimers";
    static final String QUEUE_REQUESTS = "gateleen.hook-reducedpropagation-queuerequests";

    private LuaScriptState startQueueTimerLuaScriptState;
    private LuaScriptState removeExpiredQueuesRedisCommand;

    public RedisReducedPropagationStorage(RedisClient redisClient) {
        this.redisClient = redisClient;

        startQueueTimerLuaScriptState = new LuaScriptState(ReducedPropagationLuaScripts.START_QUEUE_TIMER, redisClient, false);
        removeExpiredQueuesRedisCommand = new LuaScriptState(ReducedPropagationLuaScripts.REMOVE_EXPIRED_QUEUES, redisClient, false);
    }

    @Override
    public Future<List<String>> removeExpiredQueues(long currentTS) {
        Future<List<String>> future = Future.future();
        List<String> keys = Collections.singletonList(QUEUE_TIMERS);
        List<String> arguments = Collections.singletonList(String.valueOf(currentTS));
        RemoveExpiredQueuesRedisCommand cmd = new RemoveExpiredQueuesRedisCommand(removeExpiredQueuesRedisCommand,
                keys, arguments, redisClient, log, future);
        cmd.exec(0);
        return future;
    }

    @Override
    public Future<Boolean> addQueue(String queue, long expireTS) {
        Future<Boolean> future = Future.future();
        List<String> keys = Collections.singletonList(QUEUE_TIMERS);
        List<String> arguments = Arrays.asList(queue, String.valueOf(expireTS));
        StartQueueTimerRedisCommand cmd = new StartQueueTimerRedisCommand(startQueueTimerLuaScriptState,
                keys, arguments, redisClient, log, future);
        cmd.exec(0);
        return future;
    }

    @Override
    public Future<Void> storeQueueRequest(String queue, JsonObject queueRequest) {
        Future<Void> future = Future.future();

        if(StringUtils.isEmpty(queue)){
            future.fail("Queue is not allowed to be empty");
            return future;
        }
        if(queueRequest == null){
            future.fail("Request is not allowed to be empty");
            return future;
        }

        try {
            String queueRequestStr = queueRequest.encode();
            redisClient.hset(QUEUE_REQUESTS, queue, queueRequestStr, reply -> {
                if(reply.failed()){
                    String message = "Failed to store request for queue '"+queue+"'. Cause: " + logCause(reply);
                    log.error(message);
                    future.fail(message);
                } else {
                    future.complete();
                }
            });
        } catch (DecodeException ex){
            future.fail("Failed to decode request for queue '"+queue+"'");
            return future;
        }
        return future;
    }

    @Override
    public Future<JsonObject> getQueueRequest(String queue) {
        Future<JsonObject> future = Future.future();

        if(StringUtils.isEmpty(queue)){
            future.fail("Queue is not allowed to be empty");
            return future;
        }

        redisClient.hget(QUEUE_REQUESTS, queue, reply -> {
            if(reply.failed()){
                String message = "get queue request '"+queue+"' from hash '" + QUEUE_REQUESTS+ "' resulted in cause " + logCause(reply);
                log.error(message);
                future.fail(message);
            } else {
                String resultStr = reply.result();
                if(StringUtils.isNotEmpty(resultStr)) {
                    try {
                        JsonObject queueRequest = new JsonObject(resultStr);
                        future.complete(queueRequest);
                    } catch (DecodeException ex) {
                        future.fail("Failed to decode queue request for queue '" + queue + "'. Got this from storage: " + resultStr);
                    }
                } else {
                    future.complete(null);
                }
            }

        });
        return future;
    }

    @Override
    public Future<Void> removeQueueRequest(String queue) {
        Future<Void> future = Future.future();

        if(StringUtils.isEmpty(queue)){
            future.fail("Queue is not allowed to be empty");
            return future;
        }

        redisClient.hdel(QUEUE_REQUESTS, queue, reply -> {
            if(reply.failed()){
                String message = "Failed to remove request for queue '"+queue+"'. Cause: " + logCause(reply);
                log.error(message);
                future.fail(message);
            } else {
                future.complete();
            }
        });

        return future;
    }

    private static String logCause(AsyncResult result){
        if(result.cause() != null){
            return result.cause().getMessage();
        }
        return null;
    }
}

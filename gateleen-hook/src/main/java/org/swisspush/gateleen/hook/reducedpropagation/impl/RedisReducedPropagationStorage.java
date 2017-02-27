package org.swisspush.gateleen.hook.reducedpropagation.impl;

import io.vertx.core.Future;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.redis.RedisClient;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.hook.reducedpropagation.ReducedPropagationStorage;
import org.swisspush.gateleen.hook.reducedpropagation.lua.RemoveExpiredQueuesRedisCommand;
import org.swisspush.gateleen.hook.reducedpropagation.lua.ReducedPropagationLuaScripts;
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

    private LuaScriptState startQueueTimerLuaScriptState;
    private LuaScriptState removeExpiredQueuesRedisCommand;

    RedisReducedPropagationStorage(RedisClient redisClient) {
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
}

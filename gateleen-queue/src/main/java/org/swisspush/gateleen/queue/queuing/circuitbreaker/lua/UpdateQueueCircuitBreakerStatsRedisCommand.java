package org.swisspush.gateleen.queue.queuing.circuitbreaker.lua;

import io.vertx.core.Future;
import io.vertx.core.logging.Logger;
import io.vertx.redis.RedisClient;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.core.lua.RedisCommand;

import java.util.List;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class UpdateQueueCircuitBreakerStatsRedisCommand implements RedisCommand {

    private LuaScriptState luaScriptState;
    private List<String> keys;
    private List<String> arguments;
    private Future<String> future;
    private RedisClient redisClient;
    private Logger log;

    public UpdateQueueCircuitBreakerStatsRedisCommand(LuaScriptState luaScriptState, List<String> keys, List<String> arguments,
                                                      RedisClient redisClient, Logger log, final Future<String> future) {
        this.luaScriptState = luaScriptState;
        this.keys = keys;
        this.arguments = arguments;
        this.redisClient = redisClient;
        this.log = log;
        this.future = future;
    }

    @Override
    public void exec(int executionCounter) {
        redisClient.evalsha(luaScriptState.getSha(), keys, arguments, event -> {
            if(event.succeeded()){
                String value = event.result().getString(0);
                if (log.isTraceEnabled()) {
                    log.trace("UpdateQueueCircuitBreakerStats lua script got result: " + value);
                }
                future.complete(value);
            } else {
                String message = event.cause().getMessage();
                if(message != null && message.startsWith("NOSCRIPT")) {
                    log.warn("UpdateQueueCircuitBreakerStats script couldn't be found, reload it");
                    log.warn("amount the script got loaded: " + String.valueOf(executionCounter));
                    if(executionCounter > 10) {
                        future.fail("amount the script got loaded is higher than 10, we abort");
                    } else {
                        luaScriptState.loadLuaScript(new UpdateQueueCircuitBreakerStatsRedisCommand(luaScriptState, keys, arguments, redisClient, log, future), executionCounter);
                    }
                } else {
                    future.fail("UpdateQueueCircuitBreakerStats request failed with message: " + message);
                }
            }
        });
    }
}

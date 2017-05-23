package org.swisspush.gateleen.hook.reducedpropagation.lua;

import io.vertx.core.Future;
import org.slf4j.Logger;
import io.vertx.redis.RedisClient;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.core.lua.RedisCommand;

import java.util.List;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class StartQueueTimerRedisCommand implements RedisCommand {

    private LuaScriptState luaScriptState;
    private List<String> keys;
    private List<String> arguments;
    private Future<Boolean> future;
    private RedisClient redisClient;
    private Logger log;

    public StartQueueTimerRedisCommand(LuaScriptState luaScriptState, List<String> keys, List<String> arguments,
                                       RedisClient redisClient, Logger log, final Future<Boolean> future) {
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
                Integer insertCount = event.result().getInteger(0);
                Boolean timerStarted = insertCount != null && insertCount > 0;
                future.complete(timerStarted);
            } else {
                String message = event.cause().getMessage();
                if(message != null && message.startsWith("NOSCRIPT")) {
                    log.warn("StartQueueTimerRedisCommand script couldn't be found, reload it");
                    log.warn("amount the script got loaded: " + String.valueOf(executionCounter));
                    if(executionCounter > 10) {
                        future.fail("amount the script got loaded is higher than 10, we abort");
                    } else {
                        luaScriptState.loadLuaScript(new StartQueueTimerRedisCommand(luaScriptState, keys, arguments, redisClient, log, future), executionCounter);
                    }
                } else {
                    future.fail("StartQueueTimerRedisCommand request failed with message: " + message);
                }
            }
        });

    }
}

package org.swisspush.gateleen.hook.reducedpropagation.lua;

import io.vertx.core.Promise;
import io.vertx.redis.client.RedisAPI;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.core.lua.RedisCommand;
import org.swisspush.gateleen.core.util.RedisUtils;

import java.util.List;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class StartQueueTimerRedisCommand implements RedisCommand {

    private LuaScriptState luaScriptState;
    private List<String> keys;
    private List<String> arguments;
    private Promise<Boolean> promise;
    private RedisAPI redisAPI;
    private Logger log;

    public StartQueueTimerRedisCommand(LuaScriptState luaScriptState, List<String> keys, List<String> arguments,
                                       RedisAPI redisAPI, Logger log, final Promise<Boolean> promise) {
        this.luaScriptState = luaScriptState;
        this.keys = keys;
        this.arguments = arguments;
        this.redisAPI = redisAPI;
        this.log = log;
        this.promise = promise;
    }

    @Override
    public void exec(int executionCounter) {
        List<String> args = RedisUtils.toPayload(luaScriptState.getSha(), keys.size(), keys, arguments);
        redisAPI.evalsha(args, event -> {
            if(event.succeeded()){
                Integer insertCount = event.result().toInteger();
                Boolean timerStarted = insertCount != null && insertCount > 0;
                promise.complete(timerStarted);
            } else {
                String message = event.cause().getMessage();
                if(message != null && message.startsWith("NOSCRIPT")) {
                    log.warn("StartQueueTimerRedisCommand script couldn't be found, reload it");
                    log.warn("amount the script got loaded: " + String.valueOf(executionCounter));
                    if(executionCounter > 10) {
                        promise.fail("amount the script got loaded is higher than 10, we abort");
                    } else {
                        luaScriptState.loadLuaScript(new StartQueueTimerRedisCommand(luaScriptState, keys, arguments, redisAPI, log, promise), executionCounter);
                    }
                } else {
                    promise.fail("StartQueueTimerRedisCommand request failed with message: " + message);
                }
            }
        });

    }
}

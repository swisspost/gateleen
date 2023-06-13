package org.swisspush.gateleen.hook.reducedpropagation.lua;

import io.vertx.core.Promise;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.core.lua.RedisCommand;
import org.swisspush.gateleen.core.redis.RedisProvider;
import org.swisspush.gateleen.core.util.RedisUtils;

import java.util.List;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class StartQueueTimerRedisCommand implements RedisCommand {

    private final LuaScriptState luaScriptState;
    private final List<String> keys;
    private final List<String> arguments;
    private final Promise<Boolean> promise;
    private final RedisProvider redisProvider;
    private final Logger log;

    public StartQueueTimerRedisCommand(LuaScriptState luaScriptState, List<String> keys, List<String> arguments,
                                       RedisProvider redisProvider, Logger log, final Promise<Boolean> promise) {
        this.luaScriptState = luaScriptState;
        this.keys = keys;
        this.arguments = arguments;
        this.redisProvider = redisProvider;
        this.log = log;
        this.promise = promise;
    }

    @Override
    public void exec(int executionCounter) {
        List<String> args = RedisUtils.toPayload(luaScriptState.getSha(), keys.size(), keys, arguments);
        redisProvider.redis().onSuccess(redisAPI -> redisAPI.evalsha(args, event -> {
            if (event.succeeded()) {
                Integer insertCount = event.result().toInteger();
                Boolean timerStarted = insertCount != null && insertCount > 0;
                promise.complete(timerStarted);
            } else {
                String message = event.cause().getMessage();
                if (message != null && message.startsWith("NOSCRIPT")) {
                    log.warn("StartQueueTimerRedisCommand script couldn't be found, reload it");
                    log.warn("amount the script got loaded: " + executionCounter);
                    if (executionCounter > 10) {
                        promise.fail("amount the script got loaded is higher than 10, we abort");
                    } else {
                        luaScriptState.loadLuaScript(new StartQueueTimerRedisCommand(luaScriptState, keys, arguments,
                                redisProvider, log, promise), executionCounter);
                    }
                } else {
                    promise.fail("StartQueueTimerRedisCommand request failed with message: " + message);
                }
            }
        })).onFailure(throwable -> promise.fail("Redis: StartQueueTimerRedisCommand request failed with message: "
                + throwable.getMessage()));
    }
}

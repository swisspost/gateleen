package org.swisspush.gateleen.core.lock.lua;

import io.vertx.core.Promise;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.core.lua.RedisCommand;
import org.swisspush.gateleen.core.redis.RedisProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class ReleaseLockRedisCommand implements RedisCommand {

    private LuaScriptState luaScriptState;
    private List<String> keys;
    private List<String> arguments;
    private Promise<Boolean> promise;
    private RedisProvider redisProvider;
    private Logger log;

    public ReleaseLockRedisCommand(LuaScriptState luaScriptState, List<String> keys, List<String> arguments,
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
        List<String> args = new ArrayList<>();
        args.add(luaScriptState.getSha());
        args.add(String.valueOf(keys.size()));
        args.addAll(keys);
        args.addAll(arguments);

        redisProvider.redis().onSuccess(redisAPI -> redisAPI.evalsha(args, event -> {
            if (event.succeeded()) {
                Long unlocked = event.result().toLong();
                promise.complete(unlocked > 0);
            } else {
                String message = event.cause().getMessage();
                if (message != null && message.startsWith("NOSCRIPT")) {
                    log.warn("ReleaseLockRedisCommand script couldn't be found, reload it");
                    log.warn("amount the script got loaded: {}", executionCounter);
                    if (executionCounter > 10) {
                        promise.fail("amount the script got loaded is higher than 10, we abort");
                    } else {
                        luaScriptState.loadLuaScript(new ReleaseLockRedisCommand(luaScriptState, keys,
                                arguments, redisProvider, log, promise), executionCounter);
                    }
                } else {
                    promise.fail("ReleaseLockRedisCommand request failed with message: " + message);
                }
            }
        })).onFailure(throwable -> promise.fail("Redis: ReleaseLockRedisCommand request failed with error: " + throwable.getMessage()));
    }
}

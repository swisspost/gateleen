package org.swisspush.gateleen.cache.storage;

import io.vertx.core.Future;
import io.vertx.redis.RedisClient;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.core.lua.RedisCommand;

import java.util.List;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class CacheRequestRedisCommand implements RedisCommand {

    private final LuaScriptState luaScriptState;
    private final List<String> keys;
    private final List<String> arguments;
    private final Future<Void> future;
    private final RedisClient redisClient;
    private final Logger log;

    public CacheRequestRedisCommand(LuaScriptState luaScriptState, List<String> keys, List<String> arguments,
                                    RedisClient redisClient, Logger log, final Future<Void> future) {
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
                String resultStr = event.result().getString(0);
                if("OK".equals(resultStr)){
                    future.complete();
                } else {
                    future.fail("Cache request did not return 'OK'");
                }
            } else {
                String message = event.cause().getMessage();
                if(message != null && message.startsWith("NOSCRIPT")) {
                    log.warn("CacheRequestRedisCommand script couldn't be found, reload it");
                    log.warn("amount the script got loaded: " + executionCounter);
                    if(executionCounter > 10) {
                        future.fail("amount the script got loaded is higher than 10, we abort");
                    } else {
                        luaScriptState.loadLuaScript(new CacheRequestRedisCommand(luaScriptState, keys, arguments, redisClient, log, future), executionCounter);
                    }
                } else {
                    future.fail("CacheRequestRedisCommand request failed with message: " + message);
                }
            }
        });

    }
}

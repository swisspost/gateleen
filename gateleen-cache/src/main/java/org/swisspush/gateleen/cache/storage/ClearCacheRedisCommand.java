package org.swisspush.gateleen.cache.storage;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.redis.RedisClient;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.core.lua.RedisCommand;

import java.util.ArrayList;
import java.util.List;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class ClearCacheRedisCommand implements RedisCommand {

    private final LuaScriptState luaScriptState;
    private final List<String> keys;
    private final List<String> arguments;
    private final Future<Long> future;
    private final RedisClient redisClient;
    private final Logger log;

    public ClearCacheRedisCommand(LuaScriptState luaScriptState, List<String> keys, List<String> arguments,
                                  RedisClient redisClient, Logger log, final Future<Long> future) {
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
                Long clearedItemsCount = event.result().getLong(0);
                future.complete(clearedItemsCount);
            } else {
                String message = event.cause().getMessage();
                if(message != null && message.startsWith("NOSCRIPT")) {
                    log.warn("ClearCacheRedisCommand script couldn't be found, reload it");
                    log.warn("amount the script got loaded: " + executionCounter);
                    if(executionCounter > 10) {
                        future.fail("amount the script got loaded is higher than 10, we abort");
                    } else {
                        luaScriptState.loadLuaScript(new ClearCacheRedisCommand(luaScriptState, keys, arguments, redisClient, log, future), executionCounter);
                    }
                } else {
                    future.fail("ClearCacheRedisCommand request failed with message: " + message);
                }
            }
        });

    }
}

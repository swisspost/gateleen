package org.swisspush.gateleen.core.lock.lua;

import io.vertx.core.Future;
import io.vertx.redis.RedisClient;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.core.lua.RedisCommand;

import java.util.List;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class AcquireLockRedisCommand implements RedisCommand {

    private LuaScriptState luaScriptState;
    private List<String> keys;
    private List<String> arguments;
    private Future<Boolean> future;
    private RedisClient redisClient;
    private Logger log;

    public AcquireLockRedisCommand(LuaScriptState luaScriptState, List<String> keys, List<String> arguments,
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
                if(event.result() == null){
                    future.complete(false);
                } else {
                    String locked = event.result().getString(0);
                    future.complete("OK".equalsIgnoreCase(locked));
                }
            } else {
                String message = event.cause().getMessage();
                if(message != null && message.startsWith("NOSCRIPT")) {
                    log.warn("AcquireLockRedisCommand script couldn't be found, reload it");
                    log.warn("amount the script got loaded: " + String.valueOf(executionCounter));
                    if(executionCounter > 10) {
                        future.fail("amount the script got loaded is higher than 10, we abort");
                    } else {
                        luaScriptState.loadLuaScript(new AcquireLockRedisCommand(luaScriptState, keys, arguments, redisClient, log, future), executionCounter);
                    }
                } else {
                    future.fail("AcquireLockRedisCommand request failed with message: " + message);
                }
            }
        });
    }
}

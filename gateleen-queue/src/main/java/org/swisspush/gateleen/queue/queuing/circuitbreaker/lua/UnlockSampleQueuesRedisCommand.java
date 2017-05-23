package org.swisspush.gateleen.queue.queuing.circuitbreaker.lua;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import org.slf4j.Logger;
import io.vertx.redis.RedisClient;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.core.lua.RedisCommand;

import java.util.ArrayList;
import java.util.List;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class UnlockSampleQueuesRedisCommand implements RedisCommand {

    private LuaScriptState luaScriptState;
    private List<String> keys;
    private List<String> arguments;
    private Future<List<String>> future;
    private RedisClient redisClient;
    private Logger log;

    public UnlockSampleQueuesRedisCommand(LuaScriptState luaScriptState, List<String> keys, List<String> arguments,
                                          RedisClient redisClient, Logger log, final Future<List<String>> future) {
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
                JsonArray result = event.result();
                future.complete(new ArrayList<>(result.getList()));
            } else {
                String message = event.cause().getMessage();
                if(message != null && message.startsWith("NOSCRIPT")) {
                    log.warn("UnlockSampleQueuesRedisCommand script couldn't be found, reload it");
                    log.warn("amount the script got loaded: " + String.valueOf(executionCounter));
                    if(executionCounter > 10) {
                        future.fail("amount the script got loaded is higher than 10, we abort");
                    } else {
                        luaScriptState.loadLuaScript(new UnlockSampleQueuesRedisCommand(luaScriptState, keys, arguments, redisClient, log, future), executionCounter);
                    }
                } else {
                    future.fail("UnlockSampleQueuesRedisCommand request failed with message: " + message);
                }
            }
        });
    }
}

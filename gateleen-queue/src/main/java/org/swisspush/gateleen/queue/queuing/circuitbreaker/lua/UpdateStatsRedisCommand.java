package org.swisspush.gateleen.queue.queuing.circuitbreaker.lua;

import io.vertx.core.Future;
import org.slf4j.Logger;
import io.vertx.redis.RedisClient;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.core.lua.RedisCommand;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.UpdateStatisticsResult;

import java.util.List;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class UpdateStatsRedisCommand implements RedisCommand {

    private LuaScriptState luaScriptState;
    private List<String> keys;
    private List<String> arguments;
    private Future<UpdateStatisticsResult> future;
    private RedisClient redisClient;
    private Logger log;

    public UpdateStatsRedisCommand(LuaScriptState luaScriptState, List<String> keys, List<String> arguments,
                                   RedisClient redisClient, Logger log, final Future<UpdateStatisticsResult> future) {
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
                    log.trace("UpdateStatsRedisCommand lua script got result: " + value);
                }
                future.complete(UpdateStatisticsResult.fromString(value, UpdateStatisticsResult.ERROR));
            } else {
                String message = event.cause().getMessage();
                if(message != null && message.startsWith("NOSCRIPT")) {
                    log.warn("UpdateStatsRedisCommand script couldn't be found, reload it");
                    log.warn("amount the script got loaded: " + String.valueOf(executionCounter));
                    if(executionCounter > 10) {
                        future.fail("amount the script got loaded is higher than 10, we abort");
                    } else {
                        luaScriptState.loadLuaScript(new UpdateStatsRedisCommand(luaScriptState, keys, arguments, redisClient, log, future), executionCounter);
                    }
                } else {
                    future.fail("UpdateStatsRedisCommand request failed with message: " + message);
                }
            }
        });
    }
}

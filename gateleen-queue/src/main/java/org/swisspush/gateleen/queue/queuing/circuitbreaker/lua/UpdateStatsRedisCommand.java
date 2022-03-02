package org.swisspush.gateleen.queue.queuing.circuitbreaker.lua;

import io.vertx.core.Promise;
import io.vertx.redis.client.RedisAPI;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.core.lua.RedisCommand;
import org.swisspush.gateleen.core.util.RedisUtils;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.UpdateStatisticsResult;

import java.util.List;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class UpdateStatsRedisCommand implements RedisCommand {

    private LuaScriptState luaScriptState;
    private List<String> keys;
    private List<String> arguments;
    private Promise<UpdateStatisticsResult> promise;
    private RedisAPI redisAPI;
    private Logger log;

    public UpdateStatsRedisCommand(LuaScriptState luaScriptState, List<String> keys, List<String> arguments,
                                   RedisAPI redisAPI, Logger log, final Promise<UpdateStatisticsResult> promise) {
        this.luaScriptState = luaScriptState;
        this.keys = keys;
        this.arguments = arguments;
        this.redisAPI = redisAPI;
        this.log = log;
        this.promise = promise;
    }

    @Override
    public void exec(int executionCounter) {
        List<String> args= RedisUtils.toPayload(luaScriptState.getSha(), keys.size(), keys, arguments);
        redisAPI.evalsha(args, event -> {
            if(event.succeeded()){
                String value = event.result().toString();
                if (log.isTraceEnabled()) {
                    log.trace("UpdateStatsRedisCommand lua script got result: " + value);
                }
                promise.complete(UpdateStatisticsResult.fromString(value, UpdateStatisticsResult.ERROR));
            } else {
                String message = event.cause().getMessage();
                if(message != null && message.startsWith("NOSCRIPT")) {
                    log.warn("UpdateStatsRedisCommand script couldn't be found, reload it");
                    log.warn("amount the script got loaded: " + String.valueOf(executionCounter));
                    if(executionCounter > 10) {
                        promise.fail("amount the script got loaded is higher than 10, we abort");
                    } else {
                        luaScriptState.loadLuaScript(new UpdateStatsRedisCommand(luaScriptState, keys, arguments, redisAPI, log, promise), executionCounter);
                    }
                } else {
                    promise.fail("UpdateStatsRedisCommand request failed with message: " + message);
                }
            }
        });
    }
}

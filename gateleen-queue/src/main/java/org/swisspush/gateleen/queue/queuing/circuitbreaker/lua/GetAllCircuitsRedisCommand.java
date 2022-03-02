package org.swisspush.gateleen.queue.queuing.circuitbreaker.lua;

import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Response;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.core.lua.RedisCommand;
import org.swisspush.gateleen.core.util.RedisUtils;

import java.util.List;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class GetAllCircuitsRedisCommand implements RedisCommand {

    private LuaScriptState luaScriptState;
    private List<String> keys;
    private List<String> arguments;
    private Promise<JsonObject> promise;
    private RedisAPI redisAPI;
    private Logger log;

    public GetAllCircuitsRedisCommand(LuaScriptState luaScriptState, List<String> keys, List<String> arguments,
                                      RedisAPI redisAPI, Logger log, final Promise<JsonObject> promise) {
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
                Response response = event.result();
                try{
                    String objectStr = response.toString();
                    JsonObject obj = new JsonObject(objectStr);
                    promise.complete(obj);
                }catch (Exception e){
                    promise.fail("could not get json from lua script. cause: " + e);
                }
            } else {
                String message = event.cause().getMessage();
                if(message != null && message.startsWith("NOSCRIPT")) {
                    log.warn("GetAllCircuitsRedisCommand script couldn't be found, reload it");
                    log.warn("amount the script got loaded: {}", executionCounter);
                    if(executionCounter > 10) {
                        promise.fail("amount the script got loaded is higher than 10, we abort");
                    } else {
                        luaScriptState.loadLuaScript(new GetAllCircuitsRedisCommand(luaScriptState, keys, arguments, redisAPI, log, promise), executionCounter);
                    }
                } else {
                    promise.fail("GetAllCircuitsRedisCommand request failed with message: " + message);
                }
            }
        });
    }
}

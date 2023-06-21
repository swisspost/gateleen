package org.swisspush.gateleen.hook.reducedpropagation.lua;

import io.vertx.core.Promise;
import io.vertx.redis.client.Response;
import io.vertx.redis.client.impl.types.MultiType;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.core.lua.RedisCommand;
import org.swisspush.gateleen.core.redis.RedisProvider;
import org.swisspush.gateleen.core.util.RedisUtils;

import java.util.List;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class RemoveExpiredQueuesRedisCommand implements RedisCommand {

    private final LuaScriptState luaScriptState;
    private final List<String> keys;
    private final List<String> arguments;
    private final Promise<Response> promise;
    private final RedisProvider redisProvider;
    private final Logger log;

    public RemoveExpiredQueuesRedisCommand(LuaScriptState luaScriptState, List<String> keys, List<String> arguments,
                                           RedisProvider redisProvider, Logger log, final Promise<Response> promise) {
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
                Response response = event.result();
                if (response != null && response.size() != 0) {
                    promise.complete(response);
                } else {
                    promise.complete(MultiType.EMPTY_MULTI);
                }
            } else {
                String message = event.cause().getMessage();
                if (message != null && message.startsWith("NOSCRIPT")) {
                    log.warn("RemoveExpiredQueuesRedisCommand script couldn't be found, reload it");
                    log.warn("amount the script got loaded: " + executionCounter);
                    if (executionCounter > 10) {
                        promise.fail("amount the script got loaded is higher than 10, we abort");
                    } else {
                        luaScriptState.loadLuaScript(new RemoveExpiredQueuesRedisCommand(luaScriptState, keys, arguments,
                                redisProvider, log, promise), executionCounter);
                    }
                } else {
                    promise.fail("RemoveExpiredQueuesRedisCommand request failed with message: " + message);
                }
            }
        })).onFailure(throwable -> promise.fail("Redis: RemoveExpiredQueuesRedisCommand request failed with message: "
                + throwable.getMessage()));
    }
}

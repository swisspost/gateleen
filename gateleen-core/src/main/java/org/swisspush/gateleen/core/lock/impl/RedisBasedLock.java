package org.swisspush.gateleen.core.lock.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Response;
import io.vertx.redis.client.impl.RedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.lock.Lock;
import org.swisspush.gateleen.core.lock.lua.LockLuaScripts;
import org.swisspush.gateleen.core.lock.lua.ReleaseLockRedisCommand;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.core.util.RedisUtils;

import java.util.Collections;
import java.util.List;

/**
 * Implementation of the {@link Lock} interface based on a redis database.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class RedisBasedLock implements Lock {

    private RedisClient redisClient;
    private Logger log = LoggerFactory.getLogger(RedisBasedLock.class);

    public static final String STORAGE_PREFIX = "gateleen.core-lock:";

    private LuaScriptState releaseLockLuaScriptState;
    private RedisAPI redisAPI;

    public RedisBasedLock(RedisClient redisClient) {
        this.redisClient = redisClient;
        this.redisAPI = RedisAPI.api(redisClient);
        this.releaseLockLuaScriptState = new LuaScriptState(LockLuaScripts.LOCK_RELEASE, redisAPI, false);
    }

    private void redisSetWithOptions(String key, String value, boolean nx, long px, Handler<AsyncResult<Response>> handler) {
        JsonArray options = new JsonArray();
        options.add("PX").add(px);
        if (nx) {
            options.add("NX");
        }
        redisAPI.send(Command.SET, RedisUtils.toPayload(key, value, options).toArray(new String[0])).onComplete(handler);
    }

    @Override
    public Future<Boolean> acquireLock(String lock, String token, long lockExpiryMs) {
        Promise<Boolean> promise = Promise.promise();
        redisSetWithOptions(lock, token, true, lockExpiryMs, event -> {
            if (event.succeeded()) {
                if (event.result() != null) {
                    promise.complete("OK".equalsIgnoreCase(event.result().toString()));
                } else {
                    promise.complete(false);
                }
            } else {
                promise.fail(event.cause().getMessage());
            }
        });
        return promise.future();
    }

    @Override
    public Future<Boolean> releaseLock(String lock, String token) {
        Promise<Boolean> promise = Promise.promise();
        List<String> keys = Collections.singletonList(buildLockKey(lock));
        List<String> arguments = Collections.singletonList(token);
        ReleaseLockRedisCommand cmd = new ReleaseLockRedisCommand(releaseLockLuaScriptState,
                keys, arguments, redisAPI, log, promise);
        cmd.exec(0);
        return promise.future();
    }

    private String buildLockKey(String lock) {
        return STORAGE_PREFIX + lock;
    }
}

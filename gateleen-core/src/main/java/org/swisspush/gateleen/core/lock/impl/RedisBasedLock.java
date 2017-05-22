package org.swisspush.gateleen.core.lock.impl;

import io.vertx.core.Future;
import io.vertx.redis.RedisClient;
import io.vertx.redis.op.SetOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.lock.Lock;
import org.swisspush.gateleen.core.lock.lua.LockLuaScripts;
import org.swisspush.gateleen.core.lock.lua.ReleaseLockRedisCommand;
import org.swisspush.gateleen.core.lua.LuaScriptState;

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

    public RedisBasedLock(RedisClient redisClient) {
        this.redisClient = redisClient;

        this.releaseLockLuaScriptState = new LuaScriptState(LockLuaScripts.LOCK_RELEASE, redisClient, false);
    }

    @Override
    public Future<Boolean> acquireLock(String lock, String token, long lockExpiryMs) {
        Future<Boolean> future = Future.future();
        redisClient.setWithOptions(buildLockKey(lock), token, new SetOptions().setNX(true).setPX(lockExpiryMs), event -> {
            if(event.succeeded()){
                future.complete("OK".equalsIgnoreCase(event.result()));
            } else {
                future.fail(event.cause().getMessage());
            }
        });
        return future;
    }

    @Override
    public Future<Boolean> releaseLock(String lock, String token) {
        Future<Boolean> future = Future.future();
        List<String> keys = Collections.singletonList(buildLockKey(lock));
        List<String> arguments = Collections.singletonList(token);
        ReleaseLockRedisCommand cmd = new ReleaseLockRedisCommand(releaseLockLuaScriptState,
                keys, arguments, redisClient, log, future);
        cmd.exec(0);
        return future;
    }

    private String buildLockKey(String lock){
        return STORAGE_PREFIX + lock;
    }
}

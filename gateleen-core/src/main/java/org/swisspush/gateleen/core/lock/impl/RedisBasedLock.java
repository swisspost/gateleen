package org.swisspush.gateleen.core.lock.impl;

import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.vertx.redis.RedisClient;
import org.swisspush.gateleen.core.lock.Lock;
import org.swisspush.gateleen.core.lock.lua.LockLuaScripts;
import org.swisspush.gateleen.core.lua.LuaScriptState;

/**
 * Implementation of the {@link Lock} interface based on a redis database.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class RedisBasedLock implements Lock {

    private RedisClient redisClient;
    private Logger log = LoggerFactory.getLogger(RedisBasedLock.class);

    public static final String STORAGE_PREFIX = "gateleen.core-lock:";

    private LuaScriptState acquireLockLuaScriptState;
    private LuaScriptState releaseLockLuaScriptState;

    public RedisBasedLock(RedisClient redisClient) {
        this.redisClient = redisClient;

        this.acquireLockLuaScriptState = new LuaScriptState(LockLuaScripts.LOCK_ACQUIRE, redisClient, false);
        this.releaseLockLuaScriptState = new LuaScriptState(LockLuaScripts.LOCK_RELEASE, redisClient, false);
    }

    @Override
    public Future<Boolean> acquireLock(String lock, String token, long lockExpiryMs) {
        return null;
    }

    @Override
    public Future<Boolean> releaseLock(String lock, String token) {
        return null;
    }
}

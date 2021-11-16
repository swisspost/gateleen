package org.swisspush.gateleen.cache.storage;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.RedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.lock.Lock;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.core.util.Address;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.swisspush.gateleen.core.util.LockUtil.acquireLock;
import static org.swisspush.gateleen.core.util.LockUtil.releaseLock;

public class RedisCacheStorage implements CacheStorage {

    private Logger log = LoggerFactory.getLogger(RedisCacheStorage.class);

    private final Lock lock;
    private final RedisClient redisClient;
    private LuaScriptState clearCacheLuaScriptState;
    private LuaScriptState cacheRequestLuaScriptState;

    public static final String CACHED_REQUESTS = "gateleen.cache-cached-requests";
    public static final String CACHE_PREFIX = "gateleen.cache:";
    public static final String STORAGE_CLEANUP_TASK_LOCK = "cacheStorageCleanupTask";

    public RedisCacheStorage(Vertx vertx, Lock lock, RedisClient redisClient, long storageCleanupIntervalMs) {
        this.lock = lock;
        this.redisClient = redisClient;

        clearCacheLuaScriptState = new LuaScriptState(CacheLuaScripts.CLEAR_CACHE, redisClient, false);
        cacheRequestLuaScriptState = new LuaScriptState(CacheLuaScripts.CACHE_REQUEST, redisClient, false);

        vertx.setPeriodic(storageCleanupIntervalMs, event -> {
            String token = token(STORAGE_CLEANUP_TASK_LOCK);
            acquireLock(this.lock, STORAGE_CLEANUP_TASK_LOCK, token, lockExpiry(storageCleanupIntervalMs), log).setHandler(lockEvent -> {
                if (lockEvent.succeeded()) {
                    if (lockEvent.result()) {
                        cleanup().setHandler(cleanupResult -> {
                            if (cleanupResult.failed()) {
                                log.warn("storage cleanup has failed", cleanupResult.cause());
                                releaseLock(lock, STORAGE_CLEANUP_TASK_LOCK, token, log);
                            } else {
                                log.debug("Successfully cleaned {} entries from storage", cleanupResult.result());
                            }
                        });
                    }
                } else {
                    log.error("Could not acquire lock '{}'. Message: {}", STORAGE_CLEANUP_TASK_LOCK, lockEvent.cause().getMessage());
                }
            });
        });
    }

    private String token(String appendix) {
        return Address.instanceAddress() + "_" + System.currentTimeMillis() + "_" + appendix;
    }

    private long lockExpiry(long taskInterval) {
        if (taskInterval <= 1) {
            return 1;
        }
        return taskInterval / 2;
    }

    @Override
    public Future<Void> cacheRequest(String cacheIdentifier, JsonObject cachedObject, Duration cacheExpiry) {
        Future<Void> future = Future.future();
        List<String> keys = Collections.singletonList(CACHED_REQUESTS);
        List<String> arguments = List.of(CACHE_PREFIX, cacheIdentifier, cachedObject.encode(), String.valueOf(cacheExpiry.toMillis()));
        CacheRequestRedisCommand cmd = new CacheRequestRedisCommand(cacheRequestLuaScriptState, keys, arguments, redisClient, log, future);
        cmd.exec(0);
        return future;
    }

    @Override
    public Future<Optional<JsonObject>> cachedRequest(String cacheIdentifier) {
        Future<Optional<JsonObject>> future = Future.future();
        redisClient.get(CACHE_PREFIX + cacheIdentifier, event -> {
            if (event.failed()) {
                String message = "Failed to get cached request '" + cacheIdentifier + "'. Cause: " + logCause(event);
                log.error(message);
                future.fail(message);
            } else {
                if (event.result() == null) {
                    future.complete(Optional.empty());
                    return;
                }

                try {
                    future.complete(Optional.of(new JsonObject(event.result())));
                } catch (DecodeException ex) {
                    log.error("Failed to decode cached request", ex);
                    future.fail(ex);
                }
            }
        });
        return future;
    }

    @Override
    public Future<Long> clearCache() {
        Future<Long> future = Future.future();
        List<String> keys = Collections.singletonList(CACHED_REQUESTS);
        List<String> arguments = List.of(CACHE_PREFIX, "true");
        ClearCacheRedisCommand cmd = new ClearCacheRedisCommand(clearCacheLuaScriptState, keys, arguments, redisClient, log, future);
        cmd.exec(0);
        return future;
    }

    @Override
    public Future<Long> cacheEntriesCount() {
        Future<Long> future = Future.future();
        redisClient.scard(CACHED_REQUESTS, reply -> {
            if (reply.failed()) {
                String message = "Failed to get count of cached requests. Cause: " + logCause(reply);
                log.error(message);
                future.fail(message);
            } else {
                future.complete(reply.result());
            }
        });

        return future;
    }

    @Override
    public Future<Set<String>> cacheEntries() {
        Future<Set<String>> future = Future.future();
        redisClient.smembers(CACHED_REQUESTS, reply -> {
            if (reply.failed()) {
                String message = "Failed to get cached requests. Cause: " + logCause(reply);
                log.error(message);
                future.fail(message);
            } else {
                JsonArray array = reply.result();
                Set<String> result = IntStream.range(0, array.size())
                        .mapToObj(array::getString)
                        .map(Object::toString)
                        .collect(Collectors.toSet());
                future.complete(result);
            }
        });

        return future;
    }

    private Future<Long> cleanup() {
        Future<Long> future = Future.future();
        List<String> keys = Collections.singletonList(CACHED_REQUESTS);
        List<String> arguments = List.of(CACHE_PREFIX, "false");
        ClearCacheRedisCommand cmd = new ClearCacheRedisCommand(clearCacheLuaScriptState, keys, arguments, redisClient, log, future);
        cmd.exec(0);
        return future;
    }

    private static String logCause(AsyncResult result) {
        if (result.cause() != null) {
            return result.cause().getMessage();
        }
        return null;
    }
}

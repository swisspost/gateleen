package org.swisspush.gateleen.cache.storage;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;
import org.swisspush.gateleen.core.lock.Lock;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.core.redis.RedisProvider;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.LockUtil;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.swisspush.gateleen.core.util.LockUtil.acquireLock;

public class RedisCacheStorage implements CacheStorage {

    private Logger log = LoggerFactory.getLogger(RedisCacheStorage.class);

    private final Lock lock;
    private final LockUtil lockUtil;
    private final RedisProvider redisProvider;
    private LuaScriptState clearCacheLuaScriptState;
    private LuaScriptState cacheRequestLuaScriptState;

    public static final String CACHED_REQUESTS = "gateleen.cache-cached-requests";
    public static final String CACHE_PREFIX = "gateleen.cache:";
    public static final String STORAGE_CLEANUP_TASK_LOCK = "cacheStorageCleanupTask";

    public RedisCacheStorage(
        Vertx vertx,
        Lock lock,
        RedisProvider redisProvider,
        GateleenExceptionFactory exceptionFactory,
        long storageCleanupIntervalMs
    ) {
        this.lock = lock;
        this.lockUtil = new LockUtil(exceptionFactory);
        this.redisProvider = redisProvider;
        clearCacheLuaScriptState = new LuaScriptState(CacheLuaScripts.CLEAR_CACHE, redisProvider, exceptionFactory, false);
        cacheRequestLuaScriptState = new LuaScriptState(CacheLuaScripts.CACHE_REQUEST, redisProvider, exceptionFactory, false);

        vertx.setPeriodic(storageCleanupIntervalMs, event -> {
            String token = token(STORAGE_CLEANUP_TASK_LOCK);
            acquireLock(this.lock, STORAGE_CLEANUP_TASK_LOCK, token, lockExpiry(storageCleanupIntervalMs), log).onComplete(lockEvent -> {
                if (lockEvent.succeeded()) {
                    if (lockEvent.result()) {
                        cleanup().onComplete(cleanupResult -> {
                            if (cleanupResult.failed()) {
                                log.warn("storage cleanup has failed", cleanupResult.cause());
                                lockUtil.releaseLock(lock, STORAGE_CLEANUP_TASK_LOCK, token, log);
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
    public Future<Void> cacheRequest(String cacheIdentifier, Buffer cachedObject, Duration cacheExpiry) {
        Promise<Void> promise = Promise.promise();
        List<String> keys = Collections.singletonList(CACHED_REQUESTS);
        List<String> arguments = List.of(CACHE_PREFIX, cacheIdentifier, cachedObject.toString(), String.valueOf(cacheExpiry.toMillis()));
        CacheRequestRedisCommand cmd = new CacheRequestRedisCommand(cacheRequestLuaScriptState, keys, arguments, redisProvider, log, promise);
        cmd.exec(0);
        return promise.future();
    }

    @Override
    public Future<Optional<Buffer>> cachedRequest(String cacheIdentifier) {
        Promise<Optional<Buffer>> promise = Promise.promise();
        redisProvider.redis().onSuccess(redisAPI -> redisAPI.get(CACHE_PREFIX + cacheIdentifier, event -> {
            if (event.failed()) {
                String message = "Failed to get cached request '" + cacheIdentifier + "'. Cause: " + logCause(event);
                log.error(message);
                promise.fail(message);
            } else {
                if (event.result() != null) {
                    promise.complete(Optional.of(Buffer.buffer(event.result().toBytes())));
                } else {
                    promise.complete(Optional.empty());
                }
            }
        })).onFailure(throwable -> {
            String message = "Redis: Failed to get cached request '" + cacheIdentifier + "'. Cause: " + throwable.getMessage();
            log.error(message);
            promise.fail(message);
        });
        return promise.future();
    }

    @Override
    public Future<Long> clearCache() {
        Promise<Long> promise = Promise.promise();
        List<String> keys = Collections.singletonList(CACHED_REQUESTS);
        List<String> arguments = List.of(CACHE_PREFIX, "true");
        ClearCacheRedisCommand cmd = new ClearCacheRedisCommand(clearCacheLuaScriptState, keys, arguments, redisProvider, log, promise);
        cmd.exec(0);
        return promise.future();
    }

    @Override
    public Future<Long> cacheEntriesCount() {
        Promise<Long> promise = Promise.promise();
        redisProvider.redis().onSuccess(redisAPI -> redisAPI.scard(CACHED_REQUESTS, reply -> {
            if (reply.failed()) {
                String message = "Failed to get count of cached requests. Cause: " + logCause(reply);
                log.error(message);
                promise.fail(message);
            } else {
                promise.complete(reply.result().toLong());
            }
        })).onFailure(throwable -> {
            String message = "Redis: Failed to get count of cached requests. Cause: " + throwable.getMessage();
            log.error(message);
            promise.fail(message);
        });

        return promise.future();
    }

    @Override
    public Future<Set<String>> cacheEntries() {
        Promise<Set<String>> promise = Promise.promise();
        redisProvider.redis().onSuccess(redisAPI -> redisAPI.smembers(CACHED_REQUESTS, reply -> {
            if (reply.failed()) {
                String message = "Failed to get cached requests. Cause: " + logCause(reply);
                log.error(message);
                promise.fail(message);
            } else {
                JsonArray array = new JsonArray();
                reply.result().stream().forEach(array::add);
                Set<String> result = IntStream.range(0, array.size())
                        .mapToObj(array::getString)
                        .map(Object::toString)
                        .collect(Collectors.toSet());
                promise.complete(result);
            }
        })).onFailure(throwable -> {
            String message = "Redis: Failed to get cached requests. Cause: " + throwable.getMessage();
            log.error(message);
            promise.fail(message);
        });

        return promise.future();
    }

    private Future<Long> cleanup() {
        Promise<Long> promise = Promise.promise();
        List<String> keys = Collections.singletonList(CACHED_REQUESTS);
        List<String> arguments = List.of(CACHE_PREFIX, "false");
        ClearCacheRedisCommand cmd = new ClearCacheRedisCommand(clearCacheLuaScriptState, keys, arguments, redisProvider, log, promise);
        cmd.exec(0);
        return promise.future();
    }

    private static String logCause(AsyncResult result) {
        if (result.cause() != null) {
            return result.cause().getMessage();
        }
        return null;
    }
}

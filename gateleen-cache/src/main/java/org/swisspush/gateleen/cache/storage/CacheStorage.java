package org.swisspush.gateleen.cache.storage;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

public interface CacheStorage {

    Future<Void> cacheRequest(String cacheIdentifier, JsonObject cachedObject, Duration cacheExpiry);

    Future<Optional<String>> cachedRequest(String cacheIdentifier);

    Future<Long> clearCache();

    Future<Long> cacheEntriesCount();

    Future<Set<String>> cacheEntries();
}

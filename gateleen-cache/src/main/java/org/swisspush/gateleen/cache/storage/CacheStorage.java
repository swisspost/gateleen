package org.swisspush.gateleen.cache.storage;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

public interface CacheStorage {

    Future<Void> cacheRequest(String cacheIdentifier, Buffer cachedObject, Duration cacheExpiry);

    Future<Optional<Buffer>> cachedRequest(String cacheIdentifier);

    Future<Long> clearCache();

    Future<Long> cacheEntriesCount();

    Future<Set<String>> cacheEntries();
}

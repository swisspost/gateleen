package org.swisspush.gateleen.core.redis;

import io.vertx.core.Future;
import io.vertx.redis.client.RedisAPI;

import javax.annotation.Nullable;

/**
 * Provider for {@link RedisAPI}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public interface RedisProvider {

    Future<RedisAPI> redis();

    /**
     * Provides a {@link RedisAPI} instance for the specified storage name.
     * <p>
     * This default implementation ignores the provided storage name
     * and simply returns the default RedisAPI instance.
     * </p>
     *
     * @param storageName the name of the storage (can be null)
     * @return a Future containing the RedisAPI instance
     */
    default Future<RedisAPI> redis(@Nullable String storageName) {
        return redis();
    }
}

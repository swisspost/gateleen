package org.swisspush.gateleen.core.redis;

import io.vertx.core.Future;
import io.vertx.redis.client.RedisAPI;

import javax.annotation.Nullable;

/**
 * Provider for {@link RedisAPI} based on a provided storage name
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public interface RedisByNameProvider {

    /**
     * Provides a {@link RedisAPI} instance for the specified storage name.
     *
     * @param storageName the name of the storage (can be null)
     * @return a Future containing the RedisAPI instance
     */
    Future<RedisAPI> redis(@Nullable String storageName);
}

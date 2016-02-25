package org.swisspush.gateleen;

import redis.embedded.RedisExecProvider;
import redis.embedded.RedisServer;
import redis.embedded.util.Architecture;
import redis.embedded.util.OS;

/**
 * Starts a embedded, local redis server if needed. If a redis
 * server is already available, it will be used.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class RedisEmbeddedConfiguration {
    public final static int REDIS_PORT = 6379;

    public final static RedisExecProvider customProvider = RedisExecProvider.defaultProvider()
            .override(OS.WINDOWS, Architecture.x86, "redis/redis-server.exe")
            .override(OS.WINDOWS, Architecture.x86_64, "redis/redis-server.exe");

    public final static RedisServer redisServer = RedisServer.builder()
            .redisExecProvider(customProvider)
            .port(REDIS_PORT)
            .build();

    public static boolean useExternalRedis() {
        String externalRedis = System.getenv("EXTERNAL_REDIS");
        return externalRedis != null;
    }
}
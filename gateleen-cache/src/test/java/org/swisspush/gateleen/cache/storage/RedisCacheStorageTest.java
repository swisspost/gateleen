package org.swisspush.gateleen.cache.storage;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisOptions;
import io.vertx.redis.client.impl.RedisClient;
import org.hamcrest.core.IsEqual;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.lock.Lock;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.jayway.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.swisspush.gateleen.cache.storage.RedisCacheStorage.CACHED_REQUESTS;
import static org.swisspush.gateleen.cache.storage.RedisCacheStorage.CACHE_PREFIX;

/**
 * Tests for the {@link RedisCacheStorage} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class RedisCacheStorageTest {

    @org.junit.Rule
    public Timeout rule = Timeout.seconds(50);

    private Vertx vertx;
    private Lock lock;
    private Jedis jedis;
    private RedisCacheStorage redisCacheStorage;

    @Before
    public void setUp() {
        vertx = Vertx.vertx();

        lock = Mockito.mock(Lock.class);
        Mockito.when(lock.acquireLock(anyString(), anyString(), anyLong())).thenReturn(Future.succeededFuture(Boolean.TRUE));
        Mockito.when(lock.releaseLock(anyString(), anyString())).thenReturn(Future.succeededFuture(Boolean.TRUE));

        RedisAPI redisAPI = RedisAPI.api(new RedisClient(vertx, new RedisOptions()));

        redisCacheStorage = new RedisCacheStorage(vertx, lock, () -> Future.succeededFuture(redisAPI), 2000);
        jedis = new Jedis(new HostAndPort("localhost", 6379));
        try {
            jedis.flushAll();
        } catch (JedisConnectionException e) {
            org.junit.Assume.assumeNoException("Ignoring this test because no running redis is available. This is the case during release", e);
        }
    }

    @After
    public void tearDown() {
        if (jedis != null) {
            jedis.close();
        }
    }

    private Buffer bufferFromJson(JsonObject jsonObject) {
        return Buffer.buffer(jsonObject.encode());
    }

    private String jsonObjectStr(String value) {
        return jsonObject(value).encode();
    }

    private JsonObject jsonObject(String value) {
        return new JsonObject().put("key", value);
    }

    @Test
    public void testCacheRequest(TestContext context) {
        Async async = context.async();

        String resourceName = "cache_item_1";
        String resourceKey = CACHE_PREFIX + resourceName;

        // verify
        context.assertFalse(jedis.exists(CACHED_REQUESTS));
        context.assertFalse(jedis.exists(resourceKey));

        redisCacheStorage.cacheRequest(resourceName, bufferFromJson(new JsonObject().put("foo", "bar")), Duration.ofMillis(500)).onComplete(event -> {
            context.assertTrue(event.succeeded());

            context.assertTrue(jedis.sismember(CACHED_REQUESTS, resourceName));
            context.assertTrue(jedis.exists(resourceKey));
            context.assertTrue(jedis.pttl(resourceKey) > 400);
            context.assertEquals(new JsonObject().put("foo", "bar").encode(), jedis.get(resourceKey));

            await().atMost(1, TimeUnit.SECONDS).until(() -> jedis.exists(resourceKey), IsEqual.equalTo(false));

            async.complete();
        });
    }

    @Test
    public void testCacheRequestReplaceExisting(TestContext context) {
        Async async = context.async();

        String resourceName = "cache_item_1";
        String resourceKey = CACHE_PREFIX + resourceName;

        // verify
        context.assertFalse(jedis.exists(CACHED_REQUESTS));
        context.assertFalse(jedis.exists(resourceKey));

        redisCacheStorage.cacheRequest(resourceName, bufferFromJson(new JsonObject().put("foo", "bar")), Duration.ofMillis(500)).onComplete(event -> {
            context.assertTrue(event.succeeded());

            context.assertTrue(jedis.sismember(CACHED_REQUESTS, resourceName));
            context.assertTrue(jedis.exists(resourceKey));
            context.assertTrue(jedis.pttl(resourceKey) > 400);
            context.assertEquals(new JsonObject().put("foo", "bar").encode(), jedis.get(resourceKey));

            // replace cache entry
            redisCacheStorage.cacheRequest(resourceName, bufferFromJson(new JsonObject().put("foo", "not bar")), Duration.ofMillis(800)).onComplete(event2 -> {
                context.assertTrue(event2.succeeded());

                context.assertTrue(jedis.sismember(CACHED_REQUESTS, resourceName));
                context.assertTrue(jedis.exists(resourceKey));
                context.assertTrue(jedis.pttl(resourceKey) > 700);
                context.assertEquals(new JsonObject().put("foo", "not bar").encode(), jedis.get(resourceKey));

                await().atMost(1, TimeUnit.SECONDS).until(() -> jedis.exists(resourceKey), IsEqual.equalTo(false));

                async.complete();
            });
        });

    }

    @Test
    public void testCacheEntriesCount(TestContext context) {
        Async async = context.async();

        // prepare
        jedis.sadd(CACHED_REQUESTS, "cache_item_1", "cache_item_2", "cache_item_3");
        jedis.set(CACHE_PREFIX + "cache_item_1", jsonObjectStr("payload_1"));
        jedis.set(CACHE_PREFIX + "cache_item_2", jsonObjectStr("payload_2"));
        jedis.set(CACHE_PREFIX + "cache_item_3", jsonObjectStr("payload_3"));

        // verify
        context.assertTrue(jedis.sismember(CACHED_REQUESTS, "cache_item_1"));
        context.assertTrue(jedis.sismember(CACHED_REQUESTS, "cache_item_2"));
        context.assertTrue(jedis.sismember(CACHED_REQUESTS, "cache_item_3"));

        redisCacheStorage.cacheEntriesCount().onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(3L, event.result());
            async.complete();
        });
    }

    @Test
    public void testCachedRequest(TestContext context) {
        Async async = context.async();

        // prepare
        jedis.sadd(CACHED_REQUESTS, "cache_item_1", "cache_item_2", "cache_item_3");
        jedis.set(CACHE_PREFIX + "cache_item_1", jsonObjectStr("payload_1"));
        jedis.set(CACHE_PREFIX + "cache_item_2", jsonObjectStr("payload_2"));
        jedis.set(CACHE_PREFIX + "cache_item_3", jsonObjectStr("payload_3"));

        // verify
        context.assertTrue(jedis.sismember(CACHED_REQUESTS, "cache_item_1"));
        context.assertTrue(jedis.sismember(CACHED_REQUESTS, "cache_item_2"));
        context.assertTrue(jedis.sismember(CACHED_REQUESTS, "cache_item_3"));
        context.assertTrue(jedis.exists(CACHE_PREFIX + "cache_item_1"));
        context.assertTrue(jedis.exists(CACHE_PREFIX + "cache_item_2"));
        context.assertTrue(jedis.exists(CACHE_PREFIX + "cache_item_3"));

        redisCacheStorage.cachedRequest("cache_item_2").onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(Optional.of(bufferFromJson(jsonObject("payload_2"))), event.result());

            redisCacheStorage.cachedRequest("cache_item_99").onComplete(event1 -> {
                context.assertTrue(event1.succeeded());
                context.assertEquals(Optional.empty(), event1.result());
                async.complete();
            });
        });
    }

    @Test
    public void testCacheEntries(TestContext context) {
        Async async = context.async();

        // prepare
        jedis.sadd(CACHED_REQUESTS, "cache_item_1", "cache_item_2", "cache_item_3");
        jedis.set(CACHE_PREFIX + "cache_item_1", jsonObjectStr("payload_1"));
        jedis.set(CACHE_PREFIX + "cache_item_2", jsonObjectStr("payload_2"));
        jedis.set(CACHE_PREFIX + "cache_item_3", jsonObjectStr("payload_3"));

        // verify
        context.assertTrue(jedis.sismember(CACHED_REQUESTS, "cache_item_1"));
        context.assertTrue(jedis.sismember(CACHED_REQUESTS, "cache_item_2"));
        context.assertTrue(jedis.sismember(CACHED_REQUESTS, "cache_item_3"));

        redisCacheStorage.cacheEntries().onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(Set.of("cache_item_1", "cache_item_2", "cache_item_3"), event.result());
            async.complete();
        });
    }

    @Test
    public void testClearCache(TestContext context) {
        Async async = context.async();

        // prepare
        jedis.sadd(CACHED_REQUESTS, "cache_item_1", "cache_item_2", "cache_item_3");
        jedis.set(CACHE_PREFIX + "cache_item_1", jsonObjectStr("payload_1"));
        jedis.set(CACHE_PREFIX + "cache_item_2", jsonObjectStr("payload_2"));
        jedis.set(CACHE_PREFIX + "cache_item_3", jsonObjectStr("payload_3"));

        // verify
        context.assertTrue(jedis.sismember(CACHED_REQUESTS, "cache_item_1"));
        context.assertTrue(jedis.sismember(CACHED_REQUESTS, "cache_item_2"));
        context.assertTrue(jedis.sismember(CACHED_REQUESTS, "cache_item_3"));
        context.assertTrue(jedis.exists(CACHE_PREFIX + "cache_item_1"));
        context.assertTrue(jedis.exists(CACHE_PREFIX + "cache_item_2"));
        context.assertTrue(jedis.exists(CACHE_PREFIX + "cache_item_3"));

        redisCacheStorage.clearCache().onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(3L, event.result());

            // verify
            context.assertFalse(jedis.sismember(CACHED_REQUESTS, "cache_item_1"));
            context.assertFalse(jedis.sismember(CACHED_REQUESTS, "cache_item_2"));
            context.assertFalse(jedis.sismember(CACHED_REQUESTS, "cache_item_3"));
            context.assertFalse(jedis.exists(CACHE_PREFIX + "cache_item_1"));
            context.assertFalse(jedis.exists(CACHE_PREFIX + "cache_item_2"));
            context.assertFalse(jedis.exists(CACHE_PREFIX + "cache_item_3"));

            async.complete();
        });
    }

    @Test
    public void testClearCacheEmptyCache(TestContext context) {
        Async async = context.async();

        // verify
        context.assertEquals(0L, jedis.scard(CACHED_REQUESTS));
        context.assertFalse(jedis.exists(CACHE_PREFIX + "cache_item_1"));

        redisCacheStorage.clearCache().onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(0L, event.result());

            // verify
            context.assertEquals(0L, jedis.scard(CACHED_REQUESTS));
            context.assertFalse(jedis.exists(CACHE_PREFIX + "cache_item_1"));

            async.complete();
        });
    }

    @Test
    public void testCleanup(TestContext context) {
        // prepare
        jedis.sadd(CACHED_REQUESTS, "cache_item_1", "cache_item_2", "cache_item_3");
        jedis.set(CACHE_PREFIX + "cache_item_1", jsonObjectStr("payload_1"));
        jedis.set(CACHE_PREFIX + "cache_item_2", jsonObjectStr("payload_2"));
        jedis.set(CACHE_PREFIX + "cache_item_3", jsonObjectStr("payload_3"));

        // verify
        context.assertEquals(3L, jedis.scard(CACHED_REQUESTS));
        context.assertTrue(jedis.sismember(CACHED_REQUESTS, "cache_item_1"));
        context.assertTrue(jedis.sismember(CACHED_REQUESTS, "cache_item_2"));
        context.assertTrue(jedis.sismember(CACHED_REQUESTS, "cache_item_3"));
        context.assertTrue(jedis.exists(CACHE_PREFIX + "cache_item_1"));
        context.assertTrue(jedis.exists(CACHE_PREFIX + "cache_item_2"));
        context.assertTrue(jedis.exists(CACHE_PREFIX + "cache_item_3"));

        // delete cache entry
        jedis.del(CACHE_PREFIX + "cache_item_2");

        // wait for cleanup
        await().atMost(3, SECONDS).until(() ->
                        jedis.scard(CACHED_REQUESTS),
                equalTo(2L)
        );

        // verify that cache entry has been removed
        context.assertFalse(jedis.sismember(CACHED_REQUESTS, "cache_item_2"));
    }

}

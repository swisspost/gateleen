package org.swisspush.gateleen.hook.reducedpropagation.impl;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisOptions;
import io.vertx.redis.client.impl.RedisClient;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.util.Collections;

import static org.swisspush.gateleen.hook.reducedpropagation.impl.RedisReducedPropagationStorage.QUEUE_TIMERS;

/**
 * Testing the {@link RedisReducedPropagationStorage#removeExpiredQueues(long)} method with an empty queues set.
 * This showes a very (very) strange behaviour when running in the same test class as the other {@link RedisReducedPropagationStorage} tests.
 * <p>
 * Instead of returning an empty <code>JsonArray</code>, the test sometimes returnes a <code>JsonArray</code> with a single entry of 1 or 0.
 * No idea why!
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
@Ignore
public class RedisReducedPropagationStorageRemoveExpiredQueuesEmptyTest {

    private static Vertx vertx;
    private Jedis jedis;
    private static RedisReducedPropagationStorage storage;

    @org.junit.Rule
    public Timeout rule = Timeout.seconds(5);

    @BeforeClass
    public static void setupStorage() {
        vertx = Vertx.vertx();

        RedisAPI redisAPI = RedisAPI.api(new RedisClient(vertx, new RedisOptions()));
        storage = new RedisReducedPropagationStorage(() -> Future.succeededFuture(redisAPI));
    }

    @Before
    public void setUp() {
        jedis = new Jedis("localhost");
        try {
            jedis.flushAll();
        } catch (JedisConnectionException e) {
            org.junit.Assume.assumeNoException("Ignoring this test because no running redis is available. This is the case during release", e);
        }
    }

    @Test
    public void testRemoveExpiredQueuesEmpty(TestContext context) {
        Async async = context.async();
        context.assertFalse(jedis.exists(QUEUE_TIMERS));
        storage.removeExpiredQueues(10).onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertNotNull(event.result());
            context.assertEquals(Collections.emptyList(), event.result()); //dd
            async.complete();
        });
    }
}

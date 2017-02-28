package org.swisspush.gateleen.hook.reducedpropagation.impl;

import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.redis.RedisClient;
import io.vertx.redis.RedisOptions;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.util.Collections;

import static org.swisspush.gateleen.hook.reducedpropagation.impl.RedisReducedPropagationStorage.QUEUE_TIMERS;

/**
 * Testing the {@link RedisReducedPropagationStorage#removeExpiredQueues(long)} method with an empty queues set.
 * This showes a very (very) strange behaviour when running in the same test class as the other {@link RedisReducedPropagationStorage} tests.
 *
 * Instead of returning an empty <code>JsonArray</code>, the test sometimes returnes a <code>JsonArray</code> with a single entry of 1 or 0.
 * No idea why!
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class RedisReducedPropagationStorageRemoveExpiredQueuesEmptyTest {

    private static Vertx vertx;
    private Jedis jedis;
    private static RedisReducedPropagationStorage storage;

    @org.junit.Rule
    public Timeout rule = Timeout.seconds(5);

    @BeforeClass
    public static void setupStorage() {
        vertx = Vertx.vertx();
        storage = new RedisReducedPropagationStorage(RedisClient.create(vertx, new RedisOptions()));
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
        storage.removeExpiredQueues(10).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertNotNull(event.result());
            context.assertEquals(Collections.emptyList(), event.result()); //dd
            async.complete();
        });
    }
}

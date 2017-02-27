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
import redis.clients.jedis.Tuple;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.swisspush.gateleen.hook.reducedpropagation.impl.RedisReducedPropagationStorage.QUEUE_TIMERS;

/**
 * Tests for the {@link RedisReducedPropagationStorage} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class RedisReducedPropagationStorageTest {

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
    public void testAddQueue(TestContext context) {
        Async async = context.async();
        context.assertFalse(jedis.exists(QUEUE_TIMERS));
        storage.addQueue("queue_1", 10).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(event.result());
            context.assertTrue(jedis.exists(QUEUE_TIMERS));

            Set<Tuple> expectedTuples = new HashSet<>();
            expectedTuples.add(new Tuple("queue_1", 10.0));
            assertQueuesTimersSetContent(1, expectedTuples);
            async.complete();
        });
    }

    @Test
    public void testAddQueueNoDuplicates(TestContext context) {
        Async async = context.async();
        context.assertFalse(jedis.exists(QUEUE_TIMERS));
        storage.addQueue("queue_1", 10).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(event.result());
            context.assertTrue(jedis.exists(QUEUE_TIMERS));

            Set<Tuple> expectedTuples = new HashSet<>();
            expectedTuples.add(new Tuple("queue_1", 10.0));
            assertQueuesTimersSetContent(1, expectedTuples);

            storage.addQueue("queue_1", 20).setHandler(event1 -> {
                context.assertTrue(event1.succeeded());
                context.assertFalse(event1.result());
                context.assertTrue(jedis.exists(QUEUE_TIMERS));
                assertQueuesTimersSetContent(1, expectedTuples);
                async.complete();
            });
        });
    }

    @Test
    public void testAddQueueMultipleQueues(TestContext context) {
        Async async = context.async();
        context.assertFalse(jedis.exists(QUEUE_TIMERS));
        storage.addQueue("queue_1", 10).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(event.result());
            context.assertTrue(jedis.exists(QUEUE_TIMERS));

            Set<Tuple> expected_1 = new HashSet<>();
            expected_1.add(new Tuple("queue_1", 10.0));
            assertQueuesTimersSetContent(1, expected_1);

            // add a new (other) queue. This should be added
            storage.addQueue("queue_2", 20).setHandler(event2 -> {
                context.assertTrue(event2.succeeded());
                context.assertTrue(event2.result());
                context.assertTrue(jedis.exists(QUEUE_TIMERS));

                Set<Tuple> expected_2 = new HashSet<>();
                expected_2.add(new Tuple("queue_1", 10.0));
                expected_2.add(new Tuple("queue_2", 20.0));
                assertQueuesTimersSetContent(2, expected_2);

                // add a new (other) queue. This should be added
                storage.addQueue("queue_3", 5).setHandler(event3 -> {
                    context.assertTrue(event3.succeeded());
                    context.assertTrue(event3.result());
                    context.assertTrue(jedis.exists(QUEUE_TIMERS));

                    Set<Tuple> expected_3 = new HashSet<>();
                    expected_3.add(new Tuple("queue_1", 10.0));
                    expected_3.add(new Tuple("queue_2", 20.0));
                    expected_3.add(new Tuple("queue_3", 5.0));
                    assertQueuesTimersSetContent(3, expected_3);

                    // add an already existing queue. This should NOT be added
                    storage.addQueue("queue_1", 50).setHandler(event4 -> {
                        context.assertTrue(event4.succeeded());
                        context.assertFalse(event4.result());
                        context.assertTrue(jedis.exists(QUEUE_TIMERS));

                        Set<Tuple> expected_4 = new HashSet<>();
                        expected_4.add(new Tuple("queue_1", 10.0));
                        expected_4.add(new Tuple("queue_2", 20.0));
                        expected_4.add(new Tuple("queue_3", 5.0));
                        assertQueuesTimersSetContent(3, expected_4);
                        async.complete();
                    });
                });
            });
        });
    }

    @Test
    public void testRemoveExpiredQueuesEmpty(TestContext context) {
        Async async = context.async();
        context.assertFalse(jedis.exists(QUEUE_TIMERS));
        storage.removeExpiredQueues(10).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertNotNull(event.result());
            context.assertEquals(Collections.emptyList(), event.result());
            async.complete();
        });
    }

    @Test
    public void testRemoveExpiredQueuesNoExpiredQueuesFound(TestContext context) {
        Async async = context.async();
        context.assertFalse(jedis.exists(QUEUE_TIMERS));

        jedis.zadd(QUEUE_TIMERS, 15, "queue_1");
        jedis.zadd(QUEUE_TIMERS, 20, "queue_2");
        jedis.zadd(QUEUE_TIMERS, 25, "queue_3");

        storage.removeExpiredQueues(10).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertNotNull(event.result());
            context.assertEquals(Collections.emptyList(), event.result());

            Set<Tuple> expected = new HashSet<>();
            expected.add(new Tuple("queue_1", 15.0));
            expected.add(new Tuple("queue_2", 20.0));
            expected.add(new Tuple("queue_3", 25.0));
            assertQueuesTimersSetContent(3, expected);

            async.complete();
        });
    }

    @Test
    public void testRemoveExpiredQueuesExpiredQueuesFound(TestContext context) {
        Async async = context.async();
        context.assertFalse(jedis.exists(QUEUE_TIMERS));

        jedis.zadd(QUEUE_TIMERS, 15, "queue_1");
        jedis.zadd(QUEUE_TIMERS, 20, "queue_2");
        jedis.zadd(QUEUE_TIMERS, 25, "queue_3");
        jedis.zadd(QUEUE_TIMERS, 50, "queue_4");
        jedis.zadd(QUEUE_TIMERS, 75, "queue_5");

        storage.removeExpiredQueues(20).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertNotNull(event.result());
            context.assertEquals(Arrays.asList("queue_1", "queue_2"), event.result());

            Set<Tuple> expected = new HashSet<>();
            expected.add(new Tuple("queue_3", 25.0));
            expected.add(new Tuple("queue_4", 50.0));
            expected.add(new Tuple("queue_5", 75.0));
            assertQueuesTimersSetContent(3, expected);

            async.complete();
        });
    }

    @Test
    public void testRemoveExpiredQueuesAllQueuesExpired(TestContext context) {
        Async async = context.async();
        context.assertFalse(jedis.exists(QUEUE_TIMERS));

        jedis.zadd(QUEUE_TIMERS, 15, "queue_1");
        jedis.zadd(QUEUE_TIMERS, 20, "queue_2");
        jedis.zadd(QUEUE_TIMERS, 25, "queue_3");

        storage.removeExpiredQueues(50).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertNotNull(event.result());
            context.assertEquals(Arrays.asList("queue_1", "queue_2", "queue_3"), event.result());

            assertQueuesTimersSetContent(0, Collections.emptySet());

            async.complete();
        });
    }

    private void assertQueuesTimersSetContent(int nbrOfEntries, Set<Tuple> expectedTuples) {
        Set<Tuple> tuples = jedis.zrangeByScoreWithScores(QUEUE_TIMERS, "-inf", "+inf");
        assertThat(tuples.size(), equalTo(nbrOfEntries));
        assertThat(tuples, equalTo(expectedTuples));
    }
}

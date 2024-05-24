package org.swisspush.gateleen.hook.reducedpropagation.impl;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.tracing.TracingPolicy;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.redis.client.PoolOptions;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisStandaloneConnectOptions;
import io.vertx.redis.client.impl.RedisClient;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Tuple;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.swisspush.gateleen.core.exception.GateleenExceptionFactory.newGateleenWastefulExceptionFactory;
import static org.swisspush.gateleen.hook.reducedpropagation.impl.RedisReducedPropagationStorage.QUEUE_TIMERS;

/**
 * Testing the {@link RedisReducedPropagationStorage#addQueue(String, long)} method with multiple queues.
 * This showes a very (very) strange behaviour when running in the same test class as the other {@link RedisReducedPropagationStorage} tests.
 *
 * When executing this test locally, it succeeds every time. When running on travis-ci, it seems like it cannot be written to redis.
 * No idea why!
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class RedisReducedPropagationStorageAddQueueMultipleQueuesTest {

    private static Vertx vertx;
    private static final GateleenExceptionFactory exceptionFactory = newGateleenWastefulExceptionFactory();
    private Jedis jedis;
    private static RedisReducedPropagationStorage storage;

    @org.junit.Rule
    public Timeout rule = Timeout.seconds(5);

    @BeforeClass
    public static void setupStorage() {
        vertx = Vertx.vertx();

        RedisAPI redisAPI = RedisAPI.api(new RedisClient(vertx, new NetClientOptions(), new PoolOptions(), new RedisStandaloneConnectOptions(), TracingPolicy.IGNORE));
        storage = new RedisReducedPropagationStorage(() -> Future.succeededFuture(redisAPI), exceptionFactory);
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
    public void testAddQueueMultipleQueues(TestContext context) {
        Async async = context.async();
        context.assertFalse(jedis.exists(QUEUE_TIMERS));
        storage.addQueue("queue_1", 10).onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(event.result());
            context.assertTrue(jedis.exists(QUEUE_TIMERS));

            Set<Tuple> expected_1 = new HashSet<>();
            expected_1.add(new Tuple("queue_1", 10.0));
            assertQueuesTimersSetContent(1, expected_1);

            // add a new (other) queue. This should be added
            storage.addQueue("queue_2", 20).onComplete(event2 -> {
                context.assertTrue(event2.succeeded());
                context.assertTrue(event2.result());
                context.assertTrue(jedis.exists(QUEUE_TIMERS));

                Set<Tuple> expected_2 = new HashSet<>();
                expected_2.add(new Tuple("queue_1", 10.0));
                expected_2.add(new Tuple("queue_2", 20.0));
                assertQueuesTimersSetContent(2, expected_2);

                // add a new (other) queue. This should be added
                storage.addQueue("queue_3", 5).onComplete(event3 -> {
                    context.assertTrue(event3.succeeded());
                    context.assertTrue(event3.result());
                    context.assertTrue(jedis.exists(QUEUE_TIMERS));

                    Set<Tuple> expected_3 = new HashSet<>();
                    expected_3.add(new Tuple("queue_1", 10.0));
                    expected_3.add(new Tuple("queue_2", 20.0));
                    expected_3.add(new Tuple("queue_3", 5.0));
                    assertQueuesTimersSetContent(3, expected_3);

                    // add an already existing queue. This should NOT be added
                    storage.addQueue("queue_1", 50).onComplete(event4 -> {
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

    private void assertQueuesTimersSetContent(int nbrOfEntries, Set<Tuple> expectedTuples) {
        Set<Tuple> tuples = jedis.zrangeByScoreWithScores(QUEUE_TIMERS, "-inf", "+inf");
        assertThat(tuples.size(), equalTo(nbrOfEntries));
        assertThat(tuples, equalTo(expectedTuples));
    }
}

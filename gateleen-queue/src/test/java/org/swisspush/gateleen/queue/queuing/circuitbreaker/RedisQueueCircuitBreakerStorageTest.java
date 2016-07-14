package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.redis.RedisClient;
import io.vertx.redis.RedisOptions;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import redis.clients.jedis.Jedis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.swisspush.gateleen.queue.queuing.circuitbreaker.QueueCircuitState.*;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.RedisQueueCircuitBreakerStorage.*;

/**
 * Tests for the {@link RedisQueueCircuitBreakerStorage} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class RedisQueueCircuitBreakerStorageTest {

    private Vertx vertx;
    private Jedis jedis;
    private RedisQueueCircuitBreakerStorage storage;

    @org.junit.Rule
    public Timeout rule = Timeout.seconds(5);

    @Before
    public void setUp(){
        vertx = Vertx.vertx();
        jedis = new Jedis("localhost");
        jedis.flushAll();
        storage = new RedisQueueCircuitBreakerStorage(RedisClient.create(vertx, new RedisOptions()));
    }

    @Test
    public void testGetQueueCircuitState(TestContext context){
        Async async = context.async();
        PatternAndCircuitHash patternAndCircuitHash = buildPatternAndCircuitHash("/someCircuit", "someCircuitHash");
        storage.getQueueCircuitState(patternAndCircuitHash).setHandler(event -> {
            context.assertEquals(CLOSED, event.result());
            writeQueueCircuitStateToDatabase("someCircuitHash", HALF_OPEN);
            storage.getQueueCircuitState(patternAndCircuitHash).setHandler(event1 -> {
                context.assertEquals(HALF_OPEN, event1.result());
                async.complete();
            });
        });
    }

    @Test
    public void testUpdateStatistics(TestContext context){
        Async async = context.async();
        String circuitHash = "anotherCircuitHash";

        PatternAndCircuitHash patternAndCircuitHash = buildPatternAndCircuitHash("/anotherCircuit", circuitHash);

        context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.SUCCESS)));
        context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.FAILURE)));
        context.assertFalse(jedis.exists(STORAGE_OPEN_CIRCUITS));

        int errorThreshold = 50;
        long entriesMaxAgeMS = 10;
        long minQueueSampleCount = 1;
        long maxQueueSampleCount = 3;

        storage.updateStatistics(patternAndCircuitHash, "req_1", 1, errorThreshold, entriesMaxAgeMS, minQueueSampleCount, maxQueueSampleCount, QueueResponseType.SUCCESS).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(jedis.exists(key(circuitHash, QueueResponseType.SUCCESS)));
            context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.FAILURE)));
            context.assertEquals(1L, jedis.zcard(key(circuitHash, QueueResponseType.SUCCESS)));
            context.assertEquals(UpdateStatisticsResult.OK, event.result());
            storage.updateStatistics(patternAndCircuitHash, "req_2", 2, errorThreshold, entriesMaxAgeMS, minQueueSampleCount, maxQueueSampleCount, QueueResponseType.SUCCESS).setHandler(event1 -> {
                context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.FAILURE)));
                context.assertEquals(2L, jedis.zcard(key(circuitHash, QueueResponseType.SUCCESS)));
                context.assertEquals(UpdateStatisticsResult.OK, event1.result());
                storage.updateStatistics(patternAndCircuitHash, "req_3", 3, errorThreshold, entriesMaxAgeMS, minQueueSampleCount, maxQueueSampleCount, QueueResponseType.SUCCESS).setHandler(event2 -> {
                    context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.FAILURE)));
                    context.assertEquals(3L, jedis.zcard(key(circuitHash, QueueResponseType.SUCCESS)));
                    context.assertEquals(UpdateStatisticsResult.OK, event2.result());
                    storage.updateStatistics(patternAndCircuitHash, "req_4", 4, errorThreshold, entriesMaxAgeMS, minQueueSampleCount, maxQueueSampleCount, QueueResponseType.SUCCESS).setHandler(event3 -> {
                        context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.FAILURE)));
                        context.assertEquals(3L, jedis.zcard(key(circuitHash, QueueResponseType.SUCCESS)));
                        context.assertEquals(UpdateStatisticsResult.OK, event3.result());
                        async.complete();
                    });
                });
            });
        });
    }

    @Test
    public void testOpenCircuit(TestContext context){
        Async async = context.async();
        String circuitHash = "anotherCircuitHash";

        PatternAndCircuitHash patternAndCircuitHash = buildPatternAndCircuitHash("/anotherCircuit", circuitHash);

        context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.SUCCESS)));
        context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.FAILURE)));
        context.assertFalse(jedis.exists(STORAGE_OPEN_CIRCUITS));

        int errorThreshold = 70;
        long entriesMaxAgeMS = 10;
        long minQueueSampleCount = 3;
        long maxQueueSampleCount = 5;

        Future<UpdateStatisticsResult> f1 = storage.updateStatistics(patternAndCircuitHash, "req_1", 1, errorThreshold, entriesMaxAgeMS, minQueueSampleCount, maxQueueSampleCount, QueueResponseType.SUCCESS);
        Future<UpdateStatisticsResult> f2 = storage.updateStatistics(patternAndCircuitHash, "req_2", 2, errorThreshold, entriesMaxAgeMS, minQueueSampleCount, maxQueueSampleCount, QueueResponseType.FAILURE);
        Future<UpdateStatisticsResult> f3 = storage.updateStatistics(patternAndCircuitHash, "req_3", 3, errorThreshold, entriesMaxAgeMS, minQueueSampleCount, maxQueueSampleCount, QueueResponseType.FAILURE);

        CompositeFuture.all(f1, f2, f3).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(1L, jedis.zcard(key(circuitHash, QueueResponseType.SUCCESS)));
            context.assertEquals(2L, jedis.zcard(key(circuitHash, QueueResponseType.FAILURE)));

            storage.getQueueCircuitState(patternAndCircuitHash).setHandler(event1 -> {
                context.assertEquals(CLOSED, event1.result());
                assertStateAndErroPercentage(context, circuitHash, CLOSED, 66);
                context.assertFalse(jedis.exists(STORAGE_OPEN_CIRCUITS));
                storage.updateStatistics(patternAndCircuitHash, "req_4", 4, errorThreshold, entriesMaxAgeMS, minQueueSampleCount, maxQueueSampleCount, QueueResponseType.FAILURE).setHandler(event2 -> {
                    storage.getQueueCircuitState(patternAndCircuitHash).setHandler(event3 -> {
                        assertStateAndErroPercentage(context, circuitHash, OPEN, 75);
                        context.assertEquals(OPEN, event3.result());
                        context.assertTrue(jedis.exists(STORAGE_OPEN_CIRCUITS));
                        assertHashInOpenCircuitsSet(context, circuitHash, 1);
                        context.assertEquals(1L, jedis.zcard(key(circuitHash, QueueResponseType.SUCCESS)));
                        context.assertEquals(3L, jedis.zcard(key(circuitHash, QueueResponseType.FAILURE)));
                        async.complete();
                    });
                });
            });
        });
    }

    @Test
    public void testLockQueue(TestContext context){
        Async async = context.async();
        String circuitHash = "anotherCircuitHash";

        context.assertFalse(jedis.exists(queuesKey(circuitHash)));

        PatternAndCircuitHash patternAndCircuitHash = buildPatternAndCircuitHash("/anotherCircuit", circuitHash);
        storage.lockQueue("someQueue", patternAndCircuitHash).setHandler(event -> {
            context.assertTrue(jedis.exists(queuesKey(circuitHash)));
            context.assertEquals(1L, jedis.zcard(queuesKey(circuitHash)));
            context.assertEquals("someQueue", jedis.zrange(queuesKey(circuitHash), 0, 0).iterator().next());
            async.complete();
        });
    }

    @Test
    public void testPopQueueToUnlock(TestContext context){
        Async async = context.async();
        context.assertFalse(jedis.exists(STORAGE_QUEUES_TO_UNLOCK));
        storage.popQueueToUnlock().setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertNull(event.result());
            addToQueuesToUnlock("q3");
            addToQueuesToUnlock("q1");
            addToQueuesToUnlock("q2");
            assertQueuesToUnlockItems(context, Arrays.asList("q1", "q2", "q3"));
            storage.popQueueToUnlock().setHandler(event1 -> {
                context.assertTrue(event1.succeeded());
                context.assertEquals("q3", event1.result());
                storage.popQueueToUnlock().setHandler(event2 -> {
                    context.assertTrue(event2.succeeded());
                    context.assertEquals("q1", event2.result());
                    storage.popQueueToUnlock().setHandler(event3 -> {
                        context.assertTrue(event3.succeeded());
                        context.assertEquals("q2", event3.result());
                        storage.popQueueToUnlock().setHandler(event4 -> {
                            context.assertTrue(event4.succeeded());
                            context.assertNull(event4.result());
                            async.complete();
                        });
                    });
                });
            });
        });
    }

    @Test
    public void testCloseCircuit(TestContext context){
        Async async = context.async();
        String circuitHash = "anotherCircuitHash";

        context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.SUCCESS)));
        context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.FAILURE)));
        context.assertFalse(jedis.exists(queuesKey(circuitHash)));

        context.assertFalse(jedis.exists(STORAGE_HALFOPEN_CIRCUITS));
        context.assertFalse(jedis.exists(STORAGE_OPEN_CIRCUITS));
        context.assertFalse(jedis.exists(STORAGE_QUEUES_TO_UNLOCK));

        // prepare some test data
        writeQueueCircuitStateToDatabase(circuitHash, HALF_OPEN);
        writeQueueCircuitFailPercentageToDatabase(circuitHash, 50);

        jedis.zadd(key(circuitHash, QueueResponseType.SUCCESS), 1, "req-1");
        jedis.zadd(key(circuitHash, QueueResponseType.SUCCESS), 2, "req-2");
        jedis.zadd(key(circuitHash, QueueResponseType.SUCCESS), 3, "req-3");
        jedis.zadd(key(circuitHash, QueueResponseType.FAILURE), 4, "req-4");
        jedis.zadd(key(circuitHash, QueueResponseType.FAILURE), 5, "req-5");
        jedis.zadd(key(circuitHash, QueueResponseType.FAILURE), 6, "req-6");

        jedis.zadd(queuesKey(circuitHash), 1, "queue_1");
        jedis.zadd(queuesKey(circuitHash), 2, "queue_2");
        jedis.zadd(queuesKey(circuitHash), 3, "queue_3");

        jedis.sadd(STORAGE_HALFOPEN_CIRCUITS, "a");
        jedis.sadd(STORAGE_HALFOPEN_CIRCUITS, circuitHash);
        jedis.sadd(STORAGE_HALFOPEN_CIRCUITS, "b");
        jedis.sadd(STORAGE_HALFOPEN_CIRCUITS, "c");

        jedis.sadd(STORAGE_OPEN_CIRCUITS, "x");

        context.assertEquals(3L, jedis.zcard(key(circuitHash, QueueResponseType.SUCCESS)));
        context.assertEquals(3L, jedis.zcard(key(circuitHash, QueueResponseType.FAILURE)));
        context.assertEquals(3L, jedis.zcard(queuesKey(circuitHash)));
        context.assertEquals(4L, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
        context.assertEquals(1L, jedis.scard(STORAGE_OPEN_CIRCUITS));
        context.assertEquals(0L, jedis.llen(STORAGE_QUEUES_TO_UNLOCK));

        PatternAndCircuitHash patternAndCircuitHash = buildPatternAndCircuitHash("/anotherCircuit", circuitHash);
        storage.closeCircuit(patternAndCircuitHash).setHandler(event -> {
            context.assertTrue(event.succeeded());

            context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.SUCCESS)));
            context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.FAILURE)));
            context.assertFalse(jedis.exists(queuesKey(circuitHash)));

            context.assertTrue(jedis.exists(STORAGE_HALFOPEN_CIRCUITS));
            context.assertTrue(jedis.exists(STORAGE_QUEUES_TO_UNLOCK));

            context.assertEquals(3L, jedis.llen(STORAGE_QUEUES_TO_UNLOCK));
            context.assertEquals("queue_1", jedis.lpop(STORAGE_QUEUES_TO_UNLOCK));
            context.assertEquals("queue_2", jedis.lpop(STORAGE_QUEUES_TO_UNLOCK));
            context.assertEquals("queue_3", jedis.lpop(STORAGE_QUEUES_TO_UNLOCK));

            context.assertTrue(jedis.exists(infosKey(circuitHash)));
            assertStateAndErroPercentage(context, circuitHash, CLOSED, 0);

            context.assertEquals(3L, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
            Set<String> halfOpenCircuits = jedis.smembers(STORAGE_HALFOPEN_CIRCUITS);
            context.assertTrue(halfOpenCircuits.contains("a"));
            context.assertTrue(halfOpenCircuits.contains("b"));
            context.assertTrue(halfOpenCircuits.contains("c"));
            context.assertFalse(halfOpenCircuits.contains(circuitHash));

            context.assertEquals(1L, jedis.scard(STORAGE_OPEN_CIRCUITS));
            Set<String> openCircuits = jedis.smembers(STORAGE_OPEN_CIRCUITS);
            context.assertTrue(openCircuits.contains("x"));
            async.complete();
        });
    }

    @Test
    public void testCloseAndRemoveCircuit(TestContext context){
        Async async = context.async();
        String circuitHash = "anotherCircuitHash";

        context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.SUCCESS)));
        context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.FAILURE)));
        context.assertFalse(jedis.exists(queuesKey(circuitHash)));

        context.assertFalse(jedis.exists(STORAGE_HALFOPEN_CIRCUITS));
        context.assertFalse(jedis.exists(STORAGE_OPEN_CIRCUITS));
        context.assertFalse(jedis.exists(STORAGE_QUEUES_TO_UNLOCK));

        // prepare some test data
        writeQueueCircuitStateToDatabase(circuitHash, HALF_OPEN);
        writeQueueCircuitFailPercentageToDatabase(circuitHash, 50);

        jedis.zadd(key(circuitHash, QueueResponseType.SUCCESS), 1, "req-1");
        jedis.zadd(key(circuitHash, QueueResponseType.SUCCESS), 2, "req-2");
        jedis.zadd(key(circuitHash, QueueResponseType.SUCCESS), 3, "req-3");
        jedis.zadd(key(circuitHash, QueueResponseType.FAILURE), 4, "req-4");
        jedis.zadd(key(circuitHash, QueueResponseType.FAILURE), 5, "req-5");
        jedis.zadd(key(circuitHash, QueueResponseType.FAILURE), 6, "req-6");

        jedis.zadd(queuesKey(circuitHash), 1, "queue_1");
        jedis.zadd(queuesKey(circuitHash), 2, "queue_2");
        jedis.zadd(queuesKey(circuitHash), 3, "queue_3");

        jedis.sadd(STORAGE_HALFOPEN_CIRCUITS, "a");
        jedis.sadd(STORAGE_HALFOPEN_CIRCUITS, circuitHash);
        jedis.sadd(STORAGE_HALFOPEN_CIRCUITS, "b");
        jedis.sadd(STORAGE_HALFOPEN_CIRCUITS, "c");

        jedis.sadd(STORAGE_OPEN_CIRCUITS, "x");

        context.assertEquals(3L, jedis.zcard(key(circuitHash, QueueResponseType.SUCCESS)));
        context.assertEquals(3L, jedis.zcard(key(circuitHash, QueueResponseType.FAILURE)));
        context.assertEquals(3L, jedis.zcard(queuesKey(circuitHash)));
        context.assertEquals(4L, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
        context.assertEquals(1L, jedis.scard(STORAGE_OPEN_CIRCUITS));
        context.assertEquals(0L, jedis.llen(STORAGE_QUEUES_TO_UNLOCK));

        PatternAndCircuitHash patternAndCircuitHash = buildPatternAndCircuitHash("/anotherCircuit", circuitHash);
        storage.closeAndRemoveCircuit(patternAndCircuitHash).setHandler(event -> {
            context.assertTrue(event.succeeded());

            context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.SUCCESS)));
            context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.FAILURE)));
            context.assertFalse(jedis.exists(queuesKey(circuitHash)));

            context.assertTrue(jedis.exists(STORAGE_HALFOPEN_CIRCUITS));
            context.assertTrue(jedis.exists(STORAGE_QUEUES_TO_UNLOCK));

            context.assertEquals(3L, jedis.llen(STORAGE_QUEUES_TO_UNLOCK));
            context.assertEquals("queue_1", jedis.lpop(STORAGE_QUEUES_TO_UNLOCK));
            context.assertEquals("queue_2", jedis.lpop(STORAGE_QUEUES_TO_UNLOCK));
            context.assertEquals("queue_3", jedis.lpop(STORAGE_QUEUES_TO_UNLOCK));

            context.assertFalse(jedis.exists(infosKey(circuitHash)));
            context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.SUCCESS)));
            context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.FAILURE)));
            context.assertFalse(jedis.exists(queuesKey(circuitHash)));

            context.assertEquals(3L, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
            Set<String> halfOpenCircuits = jedis.smembers(STORAGE_HALFOPEN_CIRCUITS);
            context.assertTrue(halfOpenCircuits.contains("a"));
            context.assertTrue(halfOpenCircuits.contains("b"));
            context.assertTrue(halfOpenCircuits.contains("c"));
            context.assertFalse(halfOpenCircuits.contains(circuitHash));

            context.assertEquals(1L, jedis.scard(STORAGE_OPEN_CIRCUITS));
            Set<String> openCircuits = jedis.smembers(STORAGE_OPEN_CIRCUITS);
            context.assertTrue(openCircuits.contains("x"));
            async.complete();
        });
    }


    @Test
    public void testCloseAllCircuits(TestContext context){
        Async async = context.async();
        context.assertFalse(jedis.exists(STORAGE_HALFOPEN_CIRCUITS));
        context.assertFalse(jedis.exists(STORAGE_OPEN_CIRCUITS));
        context.assertFalse(jedis.exists(STORAGE_QUEUES_TO_UNLOCK));

        buildCircuitEntry("hash_1", HALF_OPEN, Arrays.asList("q1_1", "q1_2", "q1_3"));
        buildCircuitEntry("hash_2", OPEN,      Arrays.asList("q2_1", "q2_2", "q2_3"));
        buildCircuitEntry("hash_3", OPEN,      Arrays.asList("q3_1", "q3_2", "q3_3"));
        buildCircuitEntry("hash_4", OPEN,      Arrays.asList("q4_1", "q4_2", "q4_3"));
        buildCircuitEntry("hash_5", HALF_OPEN, Arrays.asList("q5_1", "q5_2", "q5_3"));

        context.assertEquals(2L, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
        context.assertEquals(3L, jedis.scard(STORAGE_OPEN_CIRCUITS));

        for (int index = 1; index <= 5; index++) {
            String hash = "hash_" + index;
            context.assertTrue(jedis.exists(key(hash, QueueResponseType.SUCCESS)));
            context.assertTrue(jedis.exists(key(hash, QueueResponseType.FAILURE)));
            context.assertTrue(jedis.exists(queuesKey(hash)));
        }

        storage.closeAllCircuits().setHandler(event -> {
            context.assertTrue(event.succeeded());

            context.assertEquals(0L, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
            context.assertEquals(0L, jedis.scard(STORAGE_OPEN_CIRCUITS));
            context.assertEquals(15L, jedis.llen(STORAGE_QUEUES_TO_UNLOCK));

            for (int index = 1; index <= 5; index++) {
                String hash = "hash_" + index;
                context.assertFalse(jedis.exists(queuesKey(hash)));
                assertStateAndErroPercentage(context, hash, CLOSED, 0);
                context.assertFalse(jedis.exists(key(hash, QueueResponseType.SUCCESS)));
                context.assertFalse(jedis.exists(key(hash, QueueResponseType.FAILURE)));
                context.assertFalse(jedis.exists(queuesKey(hash)));
            }

            assertQueuesToUnlockItems(context, Arrays.asList("q1_1", "q1_2", "q1_3", "q2_1", "q2_2", "q2_3",
                    "q3_1", "q3_2", "q3_3", "q4_1", "q4_2", "q4_3", "q5_1", "q5_2", "q5_3"));

            async.complete();
        });
    }

    @Test
    public void testReOpenCircuit(TestContext context){
        Async async = context.async();
        String circuitHash = "anotherCircuitHash";

        context.assertFalse(jedis.exists(STORAGE_HALFOPEN_CIRCUITS));
        context.assertFalse(jedis.exists(STORAGE_OPEN_CIRCUITS));

        // prepare some test data
        writeQueueCircuitStateToDatabase(circuitHash, HALF_OPEN);
        writeQueueCircuitFailPercentageToDatabase(circuitHash, 50);

        jedis.sadd(STORAGE_HALFOPEN_CIRCUITS, "a");
        jedis.sadd(STORAGE_HALFOPEN_CIRCUITS, circuitHash);
        jedis.sadd(STORAGE_HALFOPEN_CIRCUITS, "b");
        jedis.sadd(STORAGE_HALFOPEN_CIRCUITS, "c");

        jedis.sadd(STORAGE_OPEN_CIRCUITS, "d");
        jedis.sadd(STORAGE_OPEN_CIRCUITS, "e");
        jedis.sadd(STORAGE_OPEN_CIRCUITS, "f");
        jedis.sadd(STORAGE_OPEN_CIRCUITS, "g");
        jedis.sadd(STORAGE_OPEN_CIRCUITS, "h");

        context.assertEquals(4L, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
        context.assertEquals(5L, jedis.scard(STORAGE_OPEN_CIRCUITS));

        PatternAndCircuitHash patternAndCircuitHash = buildPatternAndCircuitHash("/anotherCircuit", circuitHash);
        storage.reOpenCircuit(patternAndCircuitHash).setHandler(event -> {
            context.assertTrue(event.succeeded());

            context.assertTrue(jedis.exists(STORAGE_HALFOPEN_CIRCUITS));
            context.assertTrue(jedis.exists(STORAGE_OPEN_CIRCUITS));

            assertStateAndErroPercentage(context, circuitHash, OPEN, 50);

            context.assertEquals(3L, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
            Set<String> halfOpenCircuits = jedis.smembers(STORAGE_HALFOPEN_CIRCUITS);
            context.assertTrue(halfOpenCircuits.contains("a"));
            context.assertTrue(halfOpenCircuits.contains("b"));
            context.assertTrue(halfOpenCircuits.contains("c"));
            context.assertFalse(halfOpenCircuits.contains(circuitHash));

            context.assertEquals(6L, jedis.scard(STORAGE_OPEN_CIRCUITS));
            Set<String> openCircuits = jedis.smembers(STORAGE_OPEN_CIRCUITS);
            context.assertTrue(openCircuits.contains("d"));
            context.assertTrue(openCircuits.contains("e"));
            context.assertTrue(openCircuits.contains("f"));
            context.assertTrue(openCircuits.contains("g"));
            context.assertTrue(openCircuits.contains("h"));
            context.assertTrue(openCircuits.contains(circuitHash));

            async.complete();
        });
    }

    @Test
    public void testSetOpenCircuitsToHalfOpen(TestContext context){
        Async async = context.async();
        context.assertFalse(jedis.exists(STORAGE_HALFOPEN_CIRCUITS));
        context.assertFalse(jedis.exists(STORAGE_OPEN_CIRCUITS));

        String h1 = "hash_1";
        String h2 = "hash_2";
        String h3 = "hash_3";
        String h4 = "hash_4";
        String h5 = "hash_5";

        buildCircuitEntry(h1, HALF_OPEN);
        buildCircuitEntry(h2, OPEN);
        buildCircuitEntry(h3, OPEN);
        buildCircuitEntry(h4, OPEN);
        buildCircuitEntry(h5, HALF_OPEN);

        context.assertEquals(2L, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
        context.assertEquals(3L, jedis.scard(STORAGE_OPEN_CIRCUITS));

        assertHashInHalfOpenCircuitsSet(context, h1, 2);
        assertHashInHalfOpenCircuitsSet(context, h5, 2);
        assertHashInOpenCircuitsSet(context, h2, 3);
        assertHashInOpenCircuitsSet(context, h3, 3);
        assertHashInOpenCircuitsSet(context, h4, 3);

        assertState(context, h1, HALF_OPEN);
        assertState(context, h2, OPEN);
        assertState(context, h3, OPEN);
        assertState(context, h4, OPEN);
        assertState(context, h5, HALF_OPEN);

        storage.setOpenCircuitsToHalfOpen().setHandler(event -> {
            context.assertTrue(event.succeeded());

            context.assertEquals(5L, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
            context.assertEquals(0L, jedis.scard(STORAGE_OPEN_CIRCUITS));

            assertHashInHalfOpenCircuitsSet(context, h1, 5);
            assertHashInHalfOpenCircuitsSet(context, h2, 5);
            assertHashInHalfOpenCircuitsSet(context, h3, 5);
            assertHashInHalfOpenCircuitsSet(context, h4, 5);
            assertHashInHalfOpenCircuitsSet(context, h5, 5);

            assertState(context, h1, HALF_OPEN);
            assertState(context, h2, HALF_OPEN);
            assertState(context, h3, HALF_OPEN);
            assertState(context, h4, HALF_OPEN);
            assertState(context, h5, HALF_OPEN);

            async.complete();
        });

    }

    private void buildCircuitEntry(String circuitHash, QueueCircuitState state){
        buildCircuitEntry(circuitHash, state, new ArrayList<>());
    }

    private void buildCircuitEntry(String circuitHash, QueueCircuitState state, List<String> queues){
        writeQueueCircuitStateToDatabase(circuitHash, state);
        writeQueueCircuitFailPercentageToDatabase(circuitHash, 50);

        if(OPEN == state){
            jedis.sadd(STORAGE_OPEN_CIRCUITS, circuitHash);
        } else if(HALF_OPEN == state){
            jedis.sadd(STORAGE_HALFOPEN_CIRCUITS, circuitHash);
        }

        jedis.zadd(key(circuitHash, QueueResponseType.SUCCESS), 1, "req-1");
        jedis.zadd(key(circuitHash, QueueResponseType.SUCCESS), 2, "req-2");
        jedis.zadd(key(circuitHash, QueueResponseType.SUCCESS), 3, "req-3");
        jedis.zadd(key(circuitHash, QueueResponseType.FAILURE), 4, "req-4");
        jedis.zadd(key(circuitHash, QueueResponseType.FAILURE), 5, "req-5");
        jedis.zadd(key(circuitHash, QueueResponseType.FAILURE), 6, "req-6");

        int index = 1;
        for (String queue : queues) {
            jedis.zadd(queuesKey(circuitHash), index, queue);
            index++;
        }
    }

    private PatternAndCircuitHash buildPatternAndCircuitHash(String pattern, String circuitHash){
        return new PatternAndCircuitHash(Pattern.compile(pattern), circuitHash);
    }

    private String key(String circuitHash, QueueResponseType queueResponseType){
        return STORAGE_PREFIX + circuitHash + queueResponseType.getKeySuffix();
    }

    private String queuesKey(String circuitHash){
        return STORAGE_PREFIX + circuitHash + STORAGE_QUEUES_SUFFIX;
    }

    private String infosKey(String circuitHash){
        return STORAGE_PREFIX + circuitHash + STORAGE_INFOS_SUFFIX;
    }

    private void writeQueueCircuitStateToDatabase(String circuitHash, QueueCircuitState state){
        jedis.hset(STORAGE_PREFIX + circuitHash + STORAGE_INFOS_SUFFIX, FIELD_STATE, state.name());
    }

    private void writeQueueCircuitFailPercentageToDatabase(String circuitHash, int failPercentage){
        jedis.hset(STORAGE_PREFIX + circuitHash + STORAGE_INFOS_SUFFIX, FIELD_FAILRATIO, String.valueOf(failPercentage));
    }

    private void assertState(TestContext context, String circuitHash, QueueCircuitState state){
        String circuitKey = STORAGE_PREFIX + circuitHash + STORAGE_INFOS_SUFFIX;
        String stateFromDb = jedis.hget(circuitKey, FIELD_STATE);
        if(stateFromDb != null){
            stateFromDb = stateFromDb.toLowerCase();
        }
        context.assertEquals(state.name().toLowerCase(), stateFromDb);
    }

    private void assertStateAndErroPercentage(TestContext context, String circuitHash, QueueCircuitState state, int percentage){
        assertState(context, circuitHash, state);
        String percentageAsString = jedis.hget(STORAGE_PREFIX + circuitHash + STORAGE_INFOS_SUFFIX, FIELD_FAILRATIO);
        context.assertEquals(percentage, Integer.valueOf(percentageAsString));
    }

    private void assertHashInOpenCircuitsSet(TestContext context, String hash, long amountOfOpenCircuits){
        Set<String> openCircuits = jedis.smembers(STORAGE_OPEN_CIRCUITS);
        context.assertTrue(openCircuits.contains(hash));
        context.assertEquals(amountOfOpenCircuits, jedis.scard(STORAGE_OPEN_CIRCUITS));
    }

    private void assertHashInHalfOpenCircuitsSet(TestContext context, String hash, long amountOfOpenCircuits){
        Set<String> openCircuits = jedis.smembers(STORAGE_HALFOPEN_CIRCUITS);
        context.assertTrue(openCircuits.contains(hash));
        context.assertEquals(amountOfOpenCircuits, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
    }

    private void assertQueuesToUnlockItems(TestContext context, List<String> items){
        List<String> queuesToUnlock = jedis.lrange(STORAGE_QUEUES_TO_UNLOCK, 0, Long.MAX_VALUE);
        context.assertEquals(items.size(), queuesToUnlock.size());
        for (String item : items) {
            context.assertTrue(queuesToUnlock.contains(item), "queuesToUnlock does not contain item " + item);
        }
    }

    private void addToQueuesToUnlock(String queueToUnlock){
        jedis.rpush(STORAGE_QUEUES_TO_UNLOCK, queueToUnlock);
    }
}

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

import java.util.Set;
import java.util.regex.Pattern;

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
        PatternAndEndpointHash patternAndEndpointHash = buildPatternAndEndpointHash("/someEndpoint", "someEndpointHash");
        storage.getQueueCircuitState(patternAndEndpointHash).setHandler(event -> {
            context.assertEquals(QueueCircuitState.CLOSED, event.result());
            writeQueueCircuitStateToDatabase("someEndpointHash", QueueCircuitState.HALF_OPEN);
            storage.getQueueCircuitState(patternAndEndpointHash).setHandler(event1 -> {
                context.assertEquals(QueueCircuitState.HALF_OPEN, event1.result());
                async.complete();
            });
        });
    }

    @Test
    public void testUpdateStatistics(TestContext context){
        Async async = context.async();
        String endpointHash = "anotherEndpointHash";

        PatternAndEndpointHash patternAndEndpointHash = buildPatternAndEndpointHash("/anotherEndpoint", endpointHash);

        context.assertFalse(jedis.exists(key(endpointHash, QueueResponseType.SUCCESS)));
        context.assertFalse(jedis.exists(key(endpointHash, QueueResponseType.FAILURE)));

        int errorThreshold = 50;
        long entriesMaxAgeMS = 10;
        long minSampleCount = 1;
        long maxSampleCount = 3;

        storage.updateStatistics(patternAndEndpointHash, "req_1", 1, errorThreshold, entriesMaxAgeMS, minSampleCount, maxSampleCount, QueueResponseType.SUCCESS).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(jedis.exists(key(endpointHash, QueueResponseType.SUCCESS)));
            context.assertFalse(jedis.exists(key(endpointHash, QueueResponseType.FAILURE)));
            context.assertEquals(1L, jedis.zcard(key(endpointHash, QueueResponseType.SUCCESS)));
            context.assertEquals(UpdateStatisticsResult.OK, event.result());
            storage.updateStatistics(patternAndEndpointHash, "req_2", 2, errorThreshold, entriesMaxAgeMS, minSampleCount, maxSampleCount, QueueResponseType.SUCCESS).setHandler(event1 -> {
                context.assertFalse(jedis.exists(key(endpointHash, QueueResponseType.FAILURE)));
                context.assertEquals(2L, jedis.zcard(key(endpointHash, QueueResponseType.SUCCESS)));
                context.assertEquals(UpdateStatisticsResult.OK, event1.result());
                storage.updateStatistics(patternAndEndpointHash, "req_3", 3, errorThreshold, entriesMaxAgeMS, minSampleCount, maxSampleCount, QueueResponseType.SUCCESS).setHandler(event2 -> {
                    context.assertFalse(jedis.exists(key(endpointHash, QueueResponseType.FAILURE)));
                    context.assertEquals(3L, jedis.zcard(key(endpointHash, QueueResponseType.SUCCESS)));
                    context.assertEquals(UpdateStatisticsResult.OK, event2.result());
                    storage.updateStatistics(patternAndEndpointHash, "req_4", 4, errorThreshold, entriesMaxAgeMS, minSampleCount, maxSampleCount, QueueResponseType.SUCCESS).setHandler(event3 -> {
                        context.assertFalse(jedis.exists(key(endpointHash, QueueResponseType.FAILURE)));
                        context.assertEquals(3L, jedis.zcard(key(endpointHash, QueueResponseType.SUCCESS)));
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
        String endpointHash = "anotherEndpointHash";

        PatternAndEndpointHash patternAndEndpointHash = buildPatternAndEndpointHash("/anotherEndpoint", endpointHash);

        context.assertFalse(jedis.exists(key(endpointHash, QueueResponseType.SUCCESS)));
        context.assertFalse(jedis.exists(key(endpointHash, QueueResponseType.FAILURE)));

        int errorThreshold = 70;
        long entriesMaxAgeMS = 10;
        long minSampleCount = 3;
        long maxSampleCount = 5;

        Future<UpdateStatisticsResult> f1 = storage.updateStatistics(patternAndEndpointHash, "req_1", 1, errorThreshold, entriesMaxAgeMS, minSampleCount, maxSampleCount, QueueResponseType.SUCCESS);
        Future<UpdateStatisticsResult> f2 = storage.updateStatistics(patternAndEndpointHash, "req_2", 2, errorThreshold, entriesMaxAgeMS, minSampleCount, maxSampleCount, QueueResponseType.FAILURE);
        Future<UpdateStatisticsResult> f3 = storage.updateStatistics(patternAndEndpointHash, "req_3", 3, errorThreshold, entriesMaxAgeMS, minSampleCount, maxSampleCount, QueueResponseType.FAILURE);

        CompositeFuture.all(f1, f2, f3).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(1L, jedis.zcard(key(endpointHash, QueueResponseType.SUCCESS)));
            context.assertEquals(2L, jedis.zcard(key(endpointHash, QueueResponseType.FAILURE)));

            storage.getQueueCircuitState(patternAndEndpointHash).setHandler(event1 -> {
                context.assertEquals(QueueCircuitState.CLOSED, event1.result());
                assertStateAndErroPercentage(context, endpointHash, "closed", 66);
                storage.updateStatistics(patternAndEndpointHash, "req_4", 4, errorThreshold, entriesMaxAgeMS, minSampleCount, maxSampleCount, QueueResponseType.FAILURE).setHandler(event2 -> {
                    storage.getQueueCircuitState(patternAndEndpointHash).setHandler(event3 -> {
                        assertStateAndErroPercentage(context, endpointHash, "open", 75);
                        context.assertEquals(QueueCircuitState.OPEN, event3.result());
                        context.assertEquals(1L, jedis.zcard(key(endpointHash, QueueResponseType.SUCCESS)));
                        context.assertEquals(3L, jedis.zcard(key(endpointHash, QueueResponseType.FAILURE)));
                        async.complete();
                    });
                });
            });
        });
    }

    @Test
    public void testLockQueue(TestContext context){
        Async async = context.async();
        String endpointHash = "anotherEndpointHash";

        context.assertFalse(jedis.exists(queuesKey(endpointHash)));

        PatternAndEndpointHash patternAndEndpointHash = buildPatternAndEndpointHash("/anotherEndpoint", endpointHash);
        storage.lockQueue("someQueue", patternAndEndpointHash).setHandler(event -> {
            context.assertTrue(jedis.exists(queuesKey(endpointHash)));
            context.assertEquals(1L, jedis.zcard(queuesKey(endpointHash)));
            context.assertEquals("someQueue", jedis.zrange(queuesKey(endpointHash), 0, 0).iterator().next());
            async.complete();
        });
    }

    @Test
    public void testCloseCircuit(TestContext context){
        Async async = context.async();
        String endpointHash = "anotherEndpointHash";

        context.assertFalse(jedis.exists(key(endpointHash, QueueResponseType.SUCCESS)));
        context.assertFalse(jedis.exists(key(endpointHash, QueueResponseType.FAILURE)));
        context.assertFalse(jedis.exists(queuesKey(endpointHash)));

        context.assertFalse(jedis.exists(STORAGE_HALFOPEN_CIRCUITS));
        context.assertFalse(jedis.exists(STORAGE_QUEUES_TO_UNLOCK));

        //prepare some test data
        writeQueueCircuitStateToDatabase(endpointHash, QueueCircuitState.HALF_OPEN);
        writeQueueCircuitFailPercentageToDatabase(endpointHash, 50);

        jedis.zadd(key(endpointHash, QueueResponseType.SUCCESS), 1, "req-1");
        jedis.zadd(key(endpointHash, QueueResponseType.SUCCESS), 2, "req-2");
        jedis.zadd(key(endpointHash, QueueResponseType.SUCCESS), 3, "req-3");
        jedis.zadd(key(endpointHash, QueueResponseType.FAILURE), 4, "req-4");
        jedis.zadd(key(endpointHash, QueueResponseType.FAILURE), 5, "req-5");
        jedis.zadd(key(endpointHash, QueueResponseType.FAILURE), 6, "req-6");

        jedis.zadd(queuesKey(endpointHash), 1, "queue_1");
        jedis.zadd(queuesKey(endpointHash), 2, "queue_2");
        jedis.zadd(queuesKey(endpointHash), 3, "queue_3");

        jedis.zadd(STORAGE_HALFOPEN_CIRCUITS, 1, "a");
        jedis.zadd(STORAGE_HALFOPEN_CIRCUITS, 2, endpointHash);
        jedis.zadd(STORAGE_HALFOPEN_CIRCUITS, 3, "b");
        jedis.zadd(STORAGE_HALFOPEN_CIRCUITS, 4, "c");

        context.assertEquals(3L, jedis.zcard(key(endpointHash, QueueResponseType.SUCCESS)));
        context.assertEquals(3L, jedis.zcard(key(endpointHash, QueueResponseType.FAILURE)));
        context.assertEquals(3L, jedis.zcard(queuesKey(endpointHash)));
        context.assertEquals(4L, jedis.zcard(STORAGE_HALFOPEN_CIRCUITS));
        context.assertEquals(0L, jedis.llen(STORAGE_QUEUES_TO_UNLOCK));

        PatternAndEndpointHash patternAndEndpointHash = buildPatternAndEndpointHash("/anotherEndpoint", endpointHash);
        storage.closeCircuit(patternAndEndpointHash).setHandler(event -> {
            context.assertTrue(event.succeeded());

            context.assertFalse(jedis.exists(key(endpointHash, QueueResponseType.SUCCESS)));
            context.assertFalse(jedis.exists(key(endpointHash, QueueResponseType.FAILURE)));
            context.assertFalse(jedis.exists(queuesKey(endpointHash)));

            context.assertTrue(jedis.exists(STORAGE_HALFOPEN_CIRCUITS));
            context.assertTrue(jedis.exists(STORAGE_QUEUES_TO_UNLOCK));

            context.assertEquals(3L, jedis.llen(STORAGE_QUEUES_TO_UNLOCK));
            context.assertEquals("queue_1", jedis.rpop(STORAGE_QUEUES_TO_UNLOCK));
            context.assertEquals("queue_2", jedis.rpop(STORAGE_QUEUES_TO_UNLOCK));
            context.assertEquals("queue_3", jedis.rpop(STORAGE_QUEUES_TO_UNLOCK));

            assertStateAndErroPercentage(context, endpointHash, "closed", 0);

            context.assertEquals(3L, jedis.zcard(STORAGE_HALFOPEN_CIRCUITS));
            Set<String> halfOpenCircuits = jedis.zrangeByScore(STORAGE_HALFOPEN_CIRCUITS, Long.MIN_VALUE, Long.MAX_VALUE);
            context.assertTrue(halfOpenCircuits.contains("a"));
            context.assertTrue(halfOpenCircuits.contains("b"));
            context.assertTrue(halfOpenCircuits.contains("c"));
            context.assertFalse(halfOpenCircuits.contains(endpointHash));

            async.complete();
        });
    }

    private PatternAndEndpointHash buildPatternAndEndpointHash(String pattern, String endpointHash){
        return new PatternAndEndpointHash(Pattern.compile(pattern), endpointHash);
    }

    private String key(String endpointHash, QueueResponseType queueResponseType){
        return STORAGE_PREFIX + endpointHash + queueResponseType.getKeySuffix();
    }

    private String queuesKey(String endpointHash){
        return STORAGE_PREFIX + endpointHash + STORAGE_QUEUES_SUFFIX;
    }

    private void writeQueueCircuitStateToDatabase(String endpointHash, QueueCircuitState state){
        jedis.hset(STORAGE_PREFIX + endpointHash + STORAGE_INFOS_SUFFIX, FIELD_STATE, state.name());
    }

    private void writeQueueCircuitFailPercentageToDatabase(String endpointHash, int failPercentage){
        jedis.hset(STORAGE_PREFIX + endpointHash + STORAGE_INFOS_SUFFIX, FIELD_FAILRATIO, String.valueOf(failPercentage));
    }

    private void assertStateAndErroPercentage(TestContext context, String endpointHash, String state, int percentage){
        String endpointKey = STORAGE_PREFIX + endpointHash + STORAGE_INFOS_SUFFIX;
        context.assertEquals(state, jedis.hget(endpointKey, "state"));
        String percentageAsString = jedis.hget(endpointKey, "failRatio");
        context.assertEquals(percentage, Integer.valueOf(percentageAsString));
    }
}

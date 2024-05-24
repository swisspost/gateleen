package org.swisspush.gateleen.queue.queuing.circuitbreaker.impl;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
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
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.PatternAndCircuitHash;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitState;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueResponseType;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.UpdateStatisticsResult;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.util.*;
import java.util.regex.Pattern;

import static org.swisspush.gateleen.core.exception.GateleenExceptionFactory.newGateleenWastefulExceptionFactory;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.impl.RedisQueueCircuitBreakerStorage.*;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitState.*;

/**
 * Tests for the {@link RedisQueueCircuitBreakerStorage} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class RedisQueueCircuitBreakerStorageTest {

    private static Vertx vertx;
    private Jedis jedis;
    private static RedisQueueCircuitBreakerStorage storage;

    @org.junit.Rule
    public Timeout rule = Timeout.seconds(5);

    @BeforeClass
    public static void setupStorage(){
        vertx = Vertx.vertx();
        RedisAPI redisAPI = RedisAPI.api(new RedisClient(vertx, new NetClientOptions(), new PoolOptions(), new RedisStandaloneConnectOptions(), TracingPolicy.IGNORE));
        storage = new RedisQueueCircuitBreakerStorage(() -> Future.succeededFuture(redisAPI), newGateleenWastefulExceptionFactory());
    }

    @Before
    public void setUp(){
        jedis = new Jedis("localhost");
        try {
            jedis.flushAll();
        } catch (JedisConnectionException e){
            org.junit.Assume.assumeNoException("Ignoring this test because no running redis is available. This is the case during release", e);
        }
    }

    @Test
    public void testGetQueueCircuitState(TestContext context){
        Async async = context.async();
        PatternAndCircuitHash patternAndCircuitHash = buildPatternAndCircuitHash("/someCircuit", "someCircuitHash");
        storage.getQueueCircuitState(patternAndCircuitHash).onComplete(event -> {
            context.assertEquals(CLOSED, event.result());
            writeQueueCircuitStateToDatabase("someCircuitHash", HALF_OPEN);
            storage.getQueueCircuitState(patternAndCircuitHash).onComplete(event1 -> {
                context.assertEquals(HALF_OPEN, event1.result());
                async.complete();
            });
        });
    }

    @Test
    public void testGetQueueCircuitStateByHash(TestContext context){
        Async async = context.async();
        storage.getQueueCircuitState("someCircuitHash").onComplete(event -> {
            context.assertEquals(CLOSED, event.result());
            writeQueueCircuitStateToDatabase("someCircuitHash", HALF_OPEN);
            storage.getQueueCircuitState("someCircuitHash").onComplete(event1 -> {
                context.assertEquals(HALF_OPEN, event1.result());
                async.complete();
            });
        });
    }

    @Test
    public void testGetQueueCircuitInformation(TestContext context){
        Async async = context.async();
        String hash = "someCircuitHash";
        storage.getQueueCircuitInformation(hash).onComplete(event -> {
            context.assertTrue(event.succeeded());
            JsonObject result = event.result();
            context.assertEquals(CLOSED.name().toLowerCase(), result.getString("status"));
            context.assertTrue(result.containsKey("info"));
            context.assertFalse(result.getJsonObject("info").containsKey("failRatio"));
            context.assertFalse(result.getJsonObject("info").containsKey("circuit"));

            writeQueueCircuit(hash, HALF_OPEN, "/some/circuit/path", 99);

            storage.getQueueCircuitInformation(hash).onComplete(event1 -> {
                context.assertTrue(event1.succeeded());
                JsonObject result1 = event1.result();
                context.assertEquals(HALF_OPEN.name().toLowerCase(), result1.getString("status"));
                context.assertTrue(result1.containsKey("info"));
                context.assertEquals(99, result1.getJsonObject("info").getInteger("failRatio"));
                context.assertEquals("/some/circuit/path", result1.getJsonObject("info").getString("circuit"));
                async.complete();
            });
        });
    }

    @Test
    public void testGetAllCircuits(TestContext context){
        Async async = context.async();

        String hash1 = "hash_1";
        String hash2 = "hash_2";
        String hash3 = "hash_3";

        context.assertFalse(jedis.exists(infosKey(hash1)));
        context.assertFalse(jedis.exists(infosKey(hash2)));
        context.assertFalse(jedis.exists(infosKey(hash3)));

        // prepare
        writeQueueCircuit(hash1, HALF_OPEN, "/path/to/hash_1", 60);
        writeQueueCircuit(hash2, CLOSED, "/path/to/hash_2", 20);
        writeQueueCircuit(hash3, OPEN, "/path/to/hash_3", 99);

        context.assertTrue(jedis.exists(infosKey(hash1)));
        context.assertTrue(jedis.exists(infosKey(hash2)));
        context.assertTrue(jedis.exists(infosKey(hash3)));

        context.assertEquals(3L, jedis.scard(STORAGE_ALL_CIRCUITS));

        context.assertEquals(HALF_OPEN.name().toLowerCase(), jedis.hget(infosKey(hash1), FIELD_STATE).toLowerCase());
        context.assertEquals("/path/to/hash_1", jedis.hget(infosKey(hash1), FIELD_CIRCUIT));
        context.assertEquals("60", jedis.hget(infosKey(hash1), FIELD_FAILRATIO));

        context.assertEquals(CLOSED.name().toLowerCase(), jedis.hget(infosKey(hash2), FIELD_STATE).toLowerCase());
        context.assertEquals("/path/to/hash_2", jedis.hget(infosKey(hash2), FIELD_CIRCUIT));
        context.assertEquals("20", jedis.hget(infosKey(hash2), FIELD_FAILRATIO));

        context.assertEquals(OPEN.name().toLowerCase(), jedis.hget(infosKey(hash3), FIELD_STATE).toLowerCase());
        context.assertEquals("/path/to/hash_3", jedis.hget(infosKey(hash3), FIELD_CIRCUIT));
        context.assertEquals("99", jedis.hget(infosKey(hash3), FIELD_FAILRATIO));

        storage.getAllCircuits().onComplete(event -> {
            context.assertTrue(event.succeeded());
            JsonObject result = event.result();
            assertJsonObjectContents(context, result, hash1, HALF_OPEN, "/path/to/hash_1", 60);
            assertJsonObjectContents(context, result, hash2, CLOSED, "/path/to/hash_2", 20);
            assertJsonObjectContents(context, result, hash3, OPEN, "/path/to/hash_3", 99);
            async.complete();
        });
    }

    @Test
    public void testGetAllCircuitsNoCircuits(TestContext context){
        Async async = context.async();
        context.assertEquals(0L, jedis.scard(STORAGE_ALL_CIRCUITS));
        storage.getAllCircuits().onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertNotNull(event.result());
            JsonObject result = event.result();
            context.assertEquals(new JsonObject(), result);
            async.complete();
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

        storage.updateStatistics(patternAndCircuitHash, "req_1", 1, errorThreshold, entriesMaxAgeMS, minQueueSampleCount, maxQueueSampleCount, QueueResponseType.SUCCESS).onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(jedis.exists(key(circuitHash, QueueResponseType.SUCCESS)));
            context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.FAILURE)));
            context.assertEquals(1L, jedis.zcard(key(circuitHash, QueueResponseType.SUCCESS)));
            context.assertEquals(UpdateStatisticsResult.OK, event.result());
            storage.updateStatistics(patternAndCircuitHash, "req_2", 2, errorThreshold, entriesMaxAgeMS, minQueueSampleCount, maxQueueSampleCount, QueueResponseType.SUCCESS).onComplete(event1 -> {
                context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.FAILURE)));
                context.assertEquals(2L, jedis.zcard(key(circuitHash, QueueResponseType.SUCCESS)));
                context.assertEquals(UpdateStatisticsResult.OK, event1.result());
                storage.updateStatistics(patternAndCircuitHash, "req_3", 3, errorThreshold, entriesMaxAgeMS, minQueueSampleCount, maxQueueSampleCount, QueueResponseType.SUCCESS).onComplete(event2 -> {
                    context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.FAILURE)));
                    context.assertEquals(3L, jedis.zcard(key(circuitHash, QueueResponseType.SUCCESS)));
                    context.assertEquals(UpdateStatisticsResult.OK, event2.result());
                    storage.updateStatistics(patternAndCircuitHash, "req_4", 4, errorThreshold, entriesMaxAgeMS, minQueueSampleCount, maxQueueSampleCount, QueueResponseType.SUCCESS).onComplete(event3 -> {
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

        CompositeFuture.all(f1, f2, f3).onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(1L, jedis.zcard(key(circuitHash, QueueResponseType.SUCCESS)));
            context.assertEquals(2L, jedis.zcard(key(circuitHash, QueueResponseType.FAILURE)));

            storage.getQueueCircuitState(patternAndCircuitHash).onComplete(event1 -> {
                context.assertEquals(CLOSED, event1.result());
                assertStateAndErroPercentage(context, circuitHash, CLOSED, 66);
                context.assertFalse(jedis.exists(STORAGE_OPEN_CIRCUITS));
                storage.updateStatistics(patternAndCircuitHash, "req_4", 4, errorThreshold, entriesMaxAgeMS, minQueueSampleCount, maxQueueSampleCount, QueueResponseType.FAILURE).onComplete(event2 -> {
                    storage.getQueueCircuitState(patternAndCircuitHash).onComplete(event3 -> {
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
        storage.lockQueue("someQueue", patternAndCircuitHash).onComplete(event -> {
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
        storage.popQueueToUnlock().onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertNull(event.result());
            addToQueuesToUnlock("q3");
            addToQueuesToUnlock("q1");
            addToQueuesToUnlock("q2");
            assertQueuesToUnlockItems(context, Arrays.asList("q1", "q2", "q3"));
            storage.popQueueToUnlock().onComplete(event1 -> {
                context.assertTrue(event1.succeeded());
                context.assertEquals("q3", event1.result());
                storage.popQueueToUnlock().onComplete(event2 -> {
                    context.assertTrue(event2.succeeded());
                    context.assertEquals("q1", event2.result());
                    storage.popQueueToUnlock().onComplete(event3 -> {
                        context.assertTrue(event3.succeeded());
                        context.assertEquals("q2", event3.result());
                        storage.popQueueToUnlock().onComplete(event4 -> {
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

        context.assertFalse(jedis.exists(STORAGE_ALL_CIRCUITS));
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

        addToCircuitsSets(HALF_OPEN, "a");
        addToCircuitsSets(HALF_OPEN, circuitHash);
        addToCircuitsSets(HALF_OPEN, "b");
        addToCircuitsSets(HALF_OPEN, "c");

        jedis.sadd(STORAGE_OPEN_CIRCUITS, "x");

        context.assertEquals(3L, jedis.zcard(key(circuitHash, QueueResponseType.SUCCESS)));
        context.assertEquals(3L, jedis.zcard(key(circuitHash, QueueResponseType.FAILURE)));
        context.assertEquals(3L, jedis.zcard(queuesKey(circuitHash)));
        context.assertEquals(4L, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
        context.assertEquals(4L, jedis.scard(STORAGE_ALL_CIRCUITS));
        context.assertEquals(1L, jedis.scard(STORAGE_OPEN_CIRCUITS));
        context.assertEquals(0L, jedis.llen(STORAGE_QUEUES_TO_UNLOCK));

        PatternAndCircuitHash patternAndCircuitHash = buildPatternAndCircuitHash("/anotherCircuit", circuitHash);
        storage.closeCircuit(patternAndCircuitHash).onComplete(event -> {
            context.assertTrue(event.succeeded());

            context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.SUCCESS)));
            context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.FAILURE)));
            context.assertFalse(jedis.exists(queuesKey(circuitHash)));

            context.assertTrue(jedis.exists(STORAGE_ALL_CIRCUITS));
            context.assertTrue(jedis.exists(STORAGE_HALFOPEN_CIRCUITS));
            context.assertTrue(jedis.exists(STORAGE_QUEUES_TO_UNLOCK));

            context.assertEquals(3L, jedis.llen(STORAGE_QUEUES_TO_UNLOCK));
            context.assertEquals("queue_1", jedis.lpop(STORAGE_QUEUES_TO_UNLOCK));
            context.assertEquals("queue_2", jedis.lpop(STORAGE_QUEUES_TO_UNLOCK));
            context.assertEquals("queue_3", jedis.lpop(STORAGE_QUEUES_TO_UNLOCK));

            context.assertTrue(jedis.exists(infosKey(circuitHash)));
            assertStateAndErroPercentage(context, circuitHash, CLOSED, 0);

            context.assertEquals(3L, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
            context.assertEquals(4L, jedis.scard(STORAGE_ALL_CIRCUITS));
            Set<String> halfOpenCircuits = jedis.smembers(STORAGE_HALFOPEN_CIRCUITS);
            context.assertTrue(halfOpenCircuits.contains("a"));
            context.assertTrue(halfOpenCircuits.contains("b"));
            context.assertTrue(halfOpenCircuits.contains("c"));
            context.assertFalse(halfOpenCircuits.contains(circuitHash));

            Set<String> allCircuits = jedis.smembers(STORAGE_ALL_CIRCUITS);
            context.assertTrue(allCircuits.contains("a"));
            context.assertTrue(allCircuits.contains("b"));
            context.assertTrue(allCircuits.contains("c"));
            context.assertTrue(allCircuits.contains(circuitHash));

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

        context.assertFalse(jedis.exists(STORAGE_ALL_CIRCUITS));
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

        addToCircuitsSets(HALF_OPEN, "a");
        addToCircuitsSets(HALF_OPEN, circuitHash);
        addToCircuitsSets(HALF_OPEN, "b");
        addToCircuitsSets(HALF_OPEN, "c");

        jedis.sadd(STORAGE_OPEN_CIRCUITS, "x");

        context.assertEquals(3L, jedis.zcard(key(circuitHash, QueueResponseType.SUCCESS)));
        context.assertEquals(3L, jedis.zcard(key(circuitHash, QueueResponseType.FAILURE)));
        context.assertEquals(3L, jedis.zcard(queuesKey(circuitHash)));
        context.assertEquals(4L, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
        context.assertEquals(4L, jedis.scard(STORAGE_ALL_CIRCUITS));
        context.assertEquals(1L, jedis.scard(STORAGE_OPEN_CIRCUITS));
        context.assertEquals(0L, jedis.llen(STORAGE_QUEUES_TO_UNLOCK));

        PatternAndCircuitHash patternAndCircuitHash = buildPatternAndCircuitHash("/anotherCircuit", circuitHash);
        storage.closeAndRemoveCircuit(patternAndCircuitHash).onComplete(event -> {
            context.assertTrue(event.succeeded());

            context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.SUCCESS)));
            context.assertFalse(jedis.exists(key(circuitHash, QueueResponseType.FAILURE)));
            context.assertFalse(jedis.exists(queuesKey(circuitHash)));

            context.assertTrue(jedis.exists(STORAGE_HALFOPEN_CIRCUITS));
            context.assertTrue(jedis.exists(STORAGE_ALL_CIRCUITS));
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
            context.assertEquals(3L, jedis.scard(STORAGE_ALL_CIRCUITS));
            Set<String> halfOpenCircuits = jedis.smembers(STORAGE_HALFOPEN_CIRCUITS);
            context.assertTrue(halfOpenCircuits.contains("a"));
            context.assertTrue(halfOpenCircuits.contains("b"));
            context.assertTrue(halfOpenCircuits.contains("c"));
            context.assertFalse(halfOpenCircuits.contains(circuitHash));

            Set<String> allCircuits = jedis.smembers(STORAGE_ALL_CIRCUITS);
            context.assertTrue(allCircuits.contains("a"));
            context.assertTrue(allCircuits.contains("b"));
            context.assertTrue(allCircuits.contains("c"));
            context.assertFalse(allCircuits.contains(circuitHash));

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
        context.assertFalse(jedis.exists(STORAGE_ALL_CIRCUITS));
        context.assertFalse(jedis.exists(STORAGE_OPEN_CIRCUITS));
        context.assertFalse(jedis.exists(STORAGE_QUEUES_TO_UNLOCK));

        buildCircuitEntry("hash_1", HALF_OPEN, Arrays.asList("q1_1", "q1_2", "q1_3"));
        buildCircuitEntry("hash_2", OPEN,      Arrays.asList("q2_1", "q2_2", "q2_3"));
        buildCircuitEntry("hash_3", OPEN,      Arrays.asList("q3_1", "q3_2", "q3_3"));
        buildCircuitEntry("hash_4", OPEN,      Arrays.asList("q4_1", "q4_2", "q4_3"));
        buildCircuitEntry("hash_5", HALF_OPEN, Arrays.asList("q5_1", "q5_2", "q5_3"));

        context.assertEquals(2L, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
        context.assertEquals(3L, jedis.scard(STORAGE_OPEN_CIRCUITS));
        context.assertEquals(5L, jedis.scard(STORAGE_ALL_CIRCUITS));

        for (int index = 1; index <= 5; index++) {
            String hash = "hash_" + index;
            context.assertTrue(jedis.exists(key(hash, QueueResponseType.SUCCESS)));
            context.assertTrue(jedis.exists(key(hash, QueueResponseType.FAILURE)));
            context.assertTrue(jedis.exists(queuesKey(hash)));
        }

        storage.closeAllCircuits().onComplete(event -> {
            context.assertTrue(event.succeeded());

            context.assertEquals(0L, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
            context.assertEquals(0L, jedis.scard(STORAGE_OPEN_CIRCUITS));
            context.assertEquals(5L, jedis.scard(STORAGE_ALL_CIRCUITS));
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
    public void testCloseAllCircuitsEmptySets(TestContext context){
        Async async = context.async();
        context.assertFalse(jedis.exists(STORAGE_HALFOPEN_CIRCUITS));
        context.assertFalse(jedis.exists(STORAGE_ALL_CIRCUITS));
        context.assertFalse(jedis.exists(STORAGE_OPEN_CIRCUITS));
        context.assertFalse(jedis.exists(STORAGE_QUEUES_TO_UNLOCK));

        context.assertEquals(0L, jedis.scard(STORAGE_ALL_CIRCUITS));
        context.assertEquals(0L, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
        context.assertEquals(0L, jedis.scard(STORAGE_OPEN_CIRCUITS));

        storage.closeAllCircuits().onComplete(event -> {
            context.assertTrue(event.succeeded());

            context.assertEquals(0L, jedis.scard(STORAGE_ALL_CIRCUITS));
            context.assertEquals(0L, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
            context.assertEquals(0L, jedis.scard(STORAGE_OPEN_CIRCUITS));
            context.assertEquals(0L, jedis.llen(STORAGE_QUEUES_TO_UNLOCK));

            async.complete();
        });
    }


    @Test
    public void testReOpenCircuit(TestContext context){
        Async async = context.async();
        String circuitHash = "anotherCircuitHash";

        context.assertFalse(jedis.exists(STORAGE_ALL_CIRCUITS));
        context.assertFalse(jedis.exists(STORAGE_HALFOPEN_CIRCUITS));
        context.assertFalse(jedis.exists(STORAGE_OPEN_CIRCUITS));

        // prepare some test data
        writeQueueCircuitStateToDatabase(circuitHash, HALF_OPEN);
        writeQueueCircuitFailPercentageToDatabase(circuitHash, 50);

        addToCircuitsSets(HALF_OPEN, "a");
        addToCircuitsSets(HALF_OPEN, circuitHash);
        addToCircuitsSets(HALF_OPEN, "b");
        addToCircuitsSets(HALF_OPEN, "c");

        addToCircuitsSets(OPEN, "d");
        addToCircuitsSets(OPEN, "e");
        addToCircuitsSets(OPEN, "f");
        addToCircuitsSets(OPEN, "g");
        addToCircuitsSets(OPEN, "h");

        context.assertEquals(9L, jedis.scard(STORAGE_ALL_CIRCUITS));
        context.assertEquals(4L, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
        context.assertEquals(5L, jedis.scard(STORAGE_OPEN_CIRCUITS));

        PatternAndCircuitHash patternAndCircuitHash = buildPatternAndCircuitHash("/anotherCircuit", circuitHash);
        storage.reOpenCircuit(patternAndCircuitHash).onComplete(event -> {
            context.assertTrue(event.succeeded());

            context.assertTrue(jedis.exists(STORAGE_ALL_CIRCUITS));
            context.assertTrue(jedis.exists(STORAGE_HALFOPEN_CIRCUITS));
            context.assertTrue(jedis.exists(STORAGE_OPEN_CIRCUITS));

            assertStateAndErroPercentage(context, circuitHash, OPEN, 50);

            context.assertEquals(3L, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
            Set<String> halfOpenCircuits = jedis.smembers(STORAGE_HALFOPEN_CIRCUITS);
            context.assertTrue(halfOpenCircuits.contains("a"));
            context.assertTrue(halfOpenCircuits.contains("b"));
            context.assertTrue(halfOpenCircuits.contains("c"));
            context.assertFalse(halfOpenCircuits.contains(circuitHash));

            context.assertEquals(9L, jedis.scard(STORAGE_ALL_CIRCUITS));
            Set<String> allCircuits = jedis.smembers(STORAGE_ALL_CIRCUITS);
            context.assertTrue(allCircuits.contains("a"));
            context.assertTrue(allCircuits.contains("b"));
            context.assertTrue(allCircuits.contains("c"));
            context.assertTrue(allCircuits.contains("d"));
            context.assertTrue(allCircuits.contains("e"));
            context.assertTrue(allCircuits.contains("f"));
            context.assertTrue(allCircuits.contains("g"));
            context.assertTrue(allCircuits.contains("h"));
            context.assertTrue(allCircuits.contains(circuitHash));

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
        context.assertFalse(jedis.exists(STORAGE_ALL_CIRCUITS));
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

        context.assertEquals(5L, jedis.scard(STORAGE_ALL_CIRCUITS));
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

        storage.setOpenCircuitsToHalfOpen().onComplete(event -> {
            context.assertTrue(event.succeeded());

            context.assertEquals(5L, jedis.scard(STORAGE_ALL_CIRCUITS));
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

    @Test
    public void testUnlockSampleQueues(TestContext context){
        Async async = context.async();

        String c1 = "circuit_1";
        String c2 = "circuit_2";
        String c3 = "circuit_3";

        context.assertFalse(jedis.exists(STORAGE_ALL_CIRCUITS));
        context.assertFalse(jedis.exists(STORAGE_HALFOPEN_CIRCUITS));

        context.assertFalse(jedis.exists(queuesKey(c1)));
        context.assertFalse(jedis.exists(queuesKey(c2)));
        context.assertFalse(jedis.exists(queuesKey(c3)));

        // prepare some test data
        addToCircuitsSets(HALF_OPEN, c1);
        addToCircuitsSets(HALF_OPEN, c2);
        addToCircuitsSets(HALF_OPEN, c3);

        jedis.zadd(queuesKey(c1), 1, "c1_1");
        jedis.zadd(queuesKey(c1), 2, "c1_2");
        jedis.zadd(queuesKey(c1), 3, "c1_3");

        jedis.zadd(queuesKey(c2), 1, "c2_1");
        jedis.zadd(queuesKey(c2), 2, "c2_2");

        jedis.zadd(queuesKey(c3), 1, "c3_1");
        jedis.zadd(queuesKey(c3), 2, "c3_2");
        jedis.zadd(queuesKey(c3), 3, "c3_3");
        jedis.zadd(queuesKey(c3), 4, "c3_4");

        context.assertEquals(3L, jedis.scard(STORAGE_ALL_CIRCUITS));
        context.assertEquals(3L, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
        context.assertEquals(3L, jedis.zcard(queuesKey(c1)));
        context.assertEquals(2L, jedis.zcard(queuesKey(c2)));
        context.assertEquals(4L, jedis.zcard(queuesKey(c3)));

        // first lua script execution
        storage.unlockSampleQueues().onComplete(event -> {
            context.assertTrue(event.succeeded());

            context.assertEquals(3, event.result().size());
            Set<String> results = new HashSet<>();
            results.add(event.result().get(0).toString());
            results.add(event.result().get(1).toString());
            results.add(event.result().get(2).toString());
            context.assertTrue(results.containsAll(Arrays.asList("c1_1", "c2_1", "c3_1")));

            context.assertEquals(3L, jedis.scard(STORAGE_ALL_CIRCUITS));
            context.assertEquals(3L, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
            context.assertEquals(3L, jedis.zcard(queuesKey(c1)));
            context.assertEquals(2L, jedis.zcard(queuesKey(c2)));
            context.assertEquals(4L, jedis.zcard(queuesKey(c3)));

            // second lua script execution
            storage.unlockSampleQueues().onComplete(event1 -> {
                context.assertTrue(event1.succeeded());

                context.assertEquals(3, event1.result().size());
                Set<String> results1 = new HashSet<>();
                results1.add(event1.result().get(0).toString());
                results1.add(event1.result().get(1).toString());
                results1.add(event1.result().get(2).toString());
                context.assertTrue(results1.containsAll(Arrays.asList("c1_2", "c2_2", "c3_2")));

                context.assertEquals(3L, jedis.scard(STORAGE_ALL_CIRCUITS));
                context.assertEquals(3L, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
                context.assertEquals(3L, jedis.zcard(queuesKey(c1)));
                context.assertEquals(2L, jedis.zcard(queuesKey(c2)));
                context.assertEquals(4L, jedis.zcard(queuesKey(c3)));

                // third lua script execution
                storage.unlockSampleQueues().onComplete(event2 -> {
                    context.assertTrue(event2.succeeded());

                    context.assertEquals(3, event2.result().size());
                    Set<String> results2 = new HashSet<>();
                    results2.add(event2.result().get(0).toString());
                    results2.add(event2.result().get(1).toString());
                    results2.add(event2.result().get(2).toString());
                    context.assertTrue(results2.containsAll(Arrays.asList("c1_3", "c2_1", "c3_3")));

                    context.assertEquals(3L, jedis.scard(STORAGE_ALL_CIRCUITS));
                    context.assertEquals(3L, jedis.scard(STORAGE_HALFOPEN_CIRCUITS));
                    context.assertEquals(3L, jedis.zcard(queuesKey(c1)));
                    context.assertEquals(2L, jedis.zcard(queuesKey(c2)));
                    context.assertEquals(4L, jedis.zcard(queuesKey(c3)));

                    async.complete();
                });
            });
        });
    }

    private void buildCircuitEntry(String circuitHash, QueueCircuitState state){
        buildCircuitEntry(circuitHash, state, new ArrayList<>());
    }

    private void addToCircuitsSets(QueueCircuitState state, String circuitHash){
        if(OPEN == state){
            jedis.sadd(STORAGE_OPEN_CIRCUITS, circuitHash);
        } else if(HALF_OPEN == state){
            jedis.sadd(STORAGE_HALFOPEN_CIRCUITS, circuitHash);
        }
        jedis.sadd(STORAGE_ALL_CIRCUITS, circuitHash);
    }

    private void buildCircuitEntry(String circuitHash, QueueCircuitState state, List<String> queues){
        writeQueueCircuitStateToDatabase(circuitHash, state);
        writeQueueCircuitFailPercentageToDatabase(circuitHash, 50);

        addToCircuitsSets(state, circuitHash);

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

    private void writeQueueCircuit(String circuitHash, QueueCircuitState state, String circuit, int failPercentage){
        writeQueueCircuitField(circuitHash, FIELD_STATE, state.name().toLowerCase());
        writeQueueCircuitField(circuitHash, FIELD_CIRCUIT, circuit);
        writeQueueCircuitField(circuitHash, FIELD_FAILRATIO, String.valueOf(failPercentage));
        jedis.sadd(STORAGE_ALL_CIRCUITS, circuitHash);
    }

    private void writeQueueCircuitStateToDatabase(String circuitHash, QueueCircuitState state){
        writeQueueCircuitField(circuitHash, FIELD_STATE, state.name().toLowerCase());
    }

    private void writeQueueCircuitField(String circuitHash, String field, String value){
        jedis.hset(STORAGE_PREFIX + circuitHash + STORAGE_INFOS_SUFFIX, field, value);
    }

    private void writeQueueCircuitFailPercentageToDatabase(String circuitHash, int failPercentage){
        writeQueueCircuitField(circuitHash, FIELD_FAILRATIO, String.valueOf(failPercentage));
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

    private void assertJsonObjectContents(TestContext context, JsonObject result, String hash, QueueCircuitState status, String circuit, int failRatio){
        context.assertTrue(result.containsKey(hash));
        context.assertTrue(result.getJsonObject(hash).containsKey("status"));
        context.assertEquals(status.name().toLowerCase(), result.getJsonObject(hash).getString("status"));
        context.assertTrue(result.getJsonObject(hash).containsKey("infos"));
        context.assertTrue(result.getJsonObject(hash).getJsonObject("infos").containsKey("circuit"));
        context.assertEquals(circuit, result.getJsonObject(hash).getJsonObject("infos").getString("circuit"));
        context.assertTrue(result.getJsonObject(hash).getJsonObject("infos").containsKey("failRatio"));
        context.assertEquals(failRatio, result.getJsonObject(hash).getJsonObject("infos").getInteger("failRatio"));
    }

    private void addToQueuesToUnlock(String queueToUnlock){
        jedis.rpush(STORAGE_QUEUES_TO_UNLOCK, queueToUnlock);
    }
}

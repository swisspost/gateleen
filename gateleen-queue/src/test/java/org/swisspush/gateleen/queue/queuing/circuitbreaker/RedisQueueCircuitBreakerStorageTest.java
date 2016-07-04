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
import org.swisspush.gateleen.core.util.HashCodeGenerator;
import redis.clients.jedis.Jedis;

import static org.swisspush.gateleen.queue.queuing.circuitbreaker.RedisQueueCircuitBreakerStorage.FIELD_STATE;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.RedisQueueCircuitBreakerStorage.STORAGE_PREFIX;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.RedisQueueCircuitBreakerStorage.STORAGE_INFOS_SUFFIX;

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
        String endpoint = "someEndpoint";
        storage.getQueueCircuitState(endpoint).setHandler(event -> {
            context.assertEquals(QueueCircuitState.CLOSED, event.result());
            writeQueueCircuitStateToDatabase(endpoint, QueueCircuitState.HALF_OPEN);
            storage.getQueueCircuitState(endpoint).setHandler(event1 -> {
                context.assertEquals(QueueCircuitState.HALF_OPEN, event1.result());
                async.complete();
            });
        });
    }

    @Test
    public void testUpdateStatistics(TestContext context){
        Async async = context.async();
        String endpoint = "anotherEndpoint";
        context.assertFalse(jedis.exists(key(endpoint, QueueResponseType.SUCCESS)));
        context.assertFalse(jedis.exists(key(endpoint, QueueResponseType.FAILURE)));

        int errorThreshold = 50;
        long entriesMaxAgeMS = 10;
        long minSampleCount = 1;
        long maxSampleCount = 3;

        storage.updateStatistics(endpoint, "req_1", 1, errorThreshold, entriesMaxAgeMS, minSampleCount, maxSampleCount, QueueResponseType.SUCCESS).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(jedis.exists(key(endpoint, QueueResponseType.SUCCESS)));
            context.assertFalse(jedis.exists(key(endpoint, QueueResponseType.FAILURE)));
            context.assertEquals(1L, jedis.zcard(key(endpoint, QueueResponseType.SUCCESS)));
            context.assertEquals("OK", event.result());
            storage.updateStatistics(endpoint, "req_2", 2, errorThreshold, entriesMaxAgeMS, minSampleCount, maxSampleCount, QueueResponseType.SUCCESS).setHandler(event1 -> {
                context.assertFalse(jedis.exists(key(endpoint, QueueResponseType.FAILURE)));
                context.assertEquals(2L, jedis.zcard(key(endpoint, QueueResponseType.SUCCESS)));
                context.assertEquals("OK", event1.result());
                storage.updateStatistics(endpoint, "req_3", 3, errorThreshold, entriesMaxAgeMS, minSampleCount, maxSampleCount, QueueResponseType.SUCCESS).setHandler(event2 -> {
                    context.assertFalse(jedis.exists(key(endpoint, QueueResponseType.FAILURE)));
                    context.assertEquals(3L, jedis.zcard(key(endpoint, QueueResponseType.SUCCESS)));
                    context.assertEquals("OK", event2.result());
                    storage.updateStatistics(endpoint, "req_4", 4, errorThreshold, entriesMaxAgeMS, minSampleCount, maxSampleCount, QueueResponseType.SUCCESS).setHandler(event3 -> {
                        context.assertFalse(jedis.exists(key(endpoint, QueueResponseType.FAILURE)));
                        context.assertEquals(3L, jedis.zcard(key(endpoint, QueueResponseType.SUCCESS)));
                        context.assertEquals("OK", event3.result());
                        async.complete();
                    });
                });
            });
        });
    }

    @Test
    public void testOpenCircuit(TestContext context){
        Async async = context.async();
        String endpoint = "anotherEndpoint";
        context.assertFalse(jedis.exists(key(endpoint, QueueResponseType.SUCCESS)));
        context.assertFalse(jedis.exists(key(endpoint, QueueResponseType.FAILURE)));

        int errorThreshold = 70;
        long entriesMaxAgeMS = 10;
        long minSampleCount = 3;
        long maxSampleCount = 5;

        Future<String> f1 = storage.updateStatistics(endpoint, "req_1", 1, errorThreshold, entriesMaxAgeMS, minSampleCount, maxSampleCount, QueueResponseType.SUCCESS);
        Future<String> f2 = storage.updateStatistics(endpoint, "req_2", 2, errorThreshold, entriesMaxAgeMS, minSampleCount, maxSampleCount, QueueResponseType.FAILURE);
        Future<String> f3 = storage.updateStatistics(endpoint, "req_3", 3, errorThreshold, entriesMaxAgeMS, minSampleCount, maxSampleCount, QueueResponseType.FAILURE);

        CompositeFuture.all(f1, f2, f3).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(1L, jedis.zcard(key(endpoint, QueueResponseType.SUCCESS)));
            context.assertEquals(2L, jedis.zcard(key(endpoint, QueueResponseType.FAILURE)));

            storage.getQueueCircuitState(endpoint).setHandler(event1 -> {
                context.assertEquals(QueueCircuitState.CLOSED, event1.result());
                assertStateAndErroPercentage(context, endpoint, "closed", 66);
                storage.updateStatistics(endpoint, "req_4", 4, errorThreshold, entriesMaxAgeMS, minSampleCount, maxSampleCount, QueueResponseType.FAILURE).setHandler(event2 -> {
                    storage.getQueueCircuitState(endpoint).setHandler(event3 -> {
                        assertStateAndErroPercentage(context, endpoint, "open", 75);
                        context.assertEquals(QueueCircuitState.OPEN, event3.result());
                        context.assertEquals(1L, jedis.zcard(key(endpoint, QueueResponseType.SUCCESS)));
                        context.assertEquals(3L, jedis.zcard(key(endpoint, QueueResponseType.FAILURE)));
                        async.complete();
                    });
                });
            });
        });
    }

    private String key(String endpoint, QueueResponseType queueResponseType){
        return STORAGE_PREFIX + HashCodeGenerator.createHashCode(endpoint) + queueResponseType.getKeySuffix();
    }

    private void writeQueueCircuitStateToDatabase(String endpoint, QueueCircuitState state){
        jedis.hset(STORAGE_PREFIX + HashCodeGenerator.createHashCode(endpoint) + STORAGE_INFOS_SUFFIX, FIELD_STATE, state.name());
    }

    private void assertStateAndErroPercentage(TestContext context, String endpoint, String state, int percentage){
        String endpointKey = STORAGE_PREFIX + HashCodeGenerator.createHashCode(endpoint) + STORAGE_INFOS_SUFFIX;
        context.assertEquals(state, jedis.hget(endpointKey, "state"));
        String percentageAsString = jedis.hget(endpointKey, "currFailurePercentage");
        context.assertEquals(percentage, Integer.valueOf(percentageAsString));
    }
}

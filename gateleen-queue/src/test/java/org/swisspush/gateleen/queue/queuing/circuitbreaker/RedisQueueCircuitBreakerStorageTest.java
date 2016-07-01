package org.swisspush.gateleen.queue.queuing.circuitbreaker;

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

import static org.swisspush.gateleen.queue.queuing.circuitbreaker.RedisQueueCircuitBreakerStorage.FIELD_STATUS;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.RedisQueueCircuitBreakerStorage.STORAGE_PREFIX;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.RedisQueueCircuitBreakerStorage.STORAGE_STATS_SUFFIX;

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

    private void writeQueueCircuitStateToDatabase(String endpoint, QueueCircuitState state){
        jedis.hset(STORAGE_PREFIX + HashCodeGenerator.createHashCode(endpoint) + STORAGE_STATS_SUFFIX, FIELD_STATUS, state.name());
    }
}

package org.swisspush.gateleen.hook.reducedpropagation.impl;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.redis.RedisClient;
import io.vertx.redis.RedisOptions;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
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
import static org.swisspush.gateleen.hook.reducedpropagation.impl.RedisReducedPropagationStorage.QUEUE_REQUESTS;
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
    public Timeout rule = Timeout.seconds(30);

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
    public void testGetQueueRequestInvalidParam(TestContext context){
        Async async = context.async();
        context.assertFalse(jedis.exists(QUEUE_REQUESTS));
        storage.getQueueRequest(null).setHandler(event -> {
            context.assertFalse(event.succeeded());
            context.assertEquals("Queue is not allowed to be empty", event.cause().getMessage());
            async.complete();
        });
    }

    @Test
    public void testGetQueueRequestNotExisting(TestContext context){
        Async async = context.async();
        context.assertFalse(jedis.exists(QUEUE_REQUESTS));
        storage.getQueueRequest("queue_1").setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertNull(event.result());
            async.complete();
        });
    }

    @Test
    @Ignore
    public void testGetQueueRequest(TestContext context){
        Async async = context.async();
        context.assertFalse(jedis.exists(QUEUE_REQUESTS));
        JsonObject expected = new JsonObject().put("myKey", 12345);
        jedis.hset(QUEUE_REQUESTS, "queue_1", expected.encode());
        storage.getQueueRequest("queue_1").setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(expected, event.result());
            async.complete();
        });
    }

    @Test
    public void testGetQueueRequestInvalidJson(TestContext context){
        Async async = context.async();
        context.assertFalse(jedis.exists(QUEUE_REQUESTS));
        jedis.hset(QUEUE_REQUESTS, "queue_1", "not_a_json_value");
        storage.getQueueRequest("queue_1").setHandler(event -> {
            context.assertFalse(event.succeeded());
            context.assertEquals("Failed to decode queue request for queue 'queue_1'. Got this from storage: not_a_json_value", event.cause().getMessage());
            async.complete();
        });
    }

    @Test
    public void testStoreQueueRequestInvalidQueueParam(TestContext context){
        Async async = context.async();
        context.assertFalse(jedis.exists(QUEUE_REQUESTS));
        storage.storeQueueRequest(null, new JsonObject()).setHandler(event -> {
            context.assertFalse(event.succeeded());
            context.assertEquals("Queue is not allowed to be empty", event.cause().getMessage());
            context.assertFalse(jedis.exists(QUEUE_REQUESTS));
            async.complete();
        });
    }

    @Test
    public void testStoreQueueRequestInvalidRequestParam(TestContext context){
        Async async = context.async();
        context.assertFalse(jedis.exists(QUEUE_REQUESTS));
        storage.storeQueueRequest("queue_1", null).setHandler(event -> {
            context.assertFalse(event.succeeded());
            context.assertEquals("Request is not allowed to be empty", event.cause().getMessage());
            context.assertFalse(jedis.exists(QUEUE_REQUESTS));
            async.complete();
        });
    }

    @Test
    public void testStoreQueueRequest(TestContext context){
        Async async = context.async();
        context.assertFalse(jedis.exists(QUEUE_REQUESTS));
        JsonObject request = new JsonObject().put("key1", 1234).put("key2", "abcd");
        storage.storeQueueRequest("queue_1", request).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(jedis.exists(QUEUE_REQUESTS));
            context.assertTrue(jedis.hexists(QUEUE_REQUESTS, "queue_1"));
            context.assertEquals(request.encode(), jedis.hget(QUEUE_REQUESTS, "queue_1"));
            async.complete();
        });
    }

    @Test
    public void testRemoveQueueRequestInvalidQueueParam(TestContext context){
        Async async = context.async();
        context.assertFalse(jedis.exists(QUEUE_REQUESTS));
        storage.removeQueueRequest(null).setHandler(event -> {
            context.assertFalse(event.succeeded());
            context.assertEquals("Queue is not allowed to be empty", event.cause().getMessage());
            context.assertFalse(jedis.exists(QUEUE_REQUESTS));
            async.complete();
        });
    }

    @Test
    public void testRemoveQueueRequestHashNotExisting(TestContext context){
        Async async = context.async();
        context.assertFalse(jedis.exists(QUEUE_REQUESTS));
        storage.removeQueueRequest("not_existing_queue").setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertFalse(jedis.exists(QUEUE_REQUESTS));
            async.complete();
        });
    }

    @Test
    public void testRemoveQueueRequestNotExisting(TestContext context){
        Async async = context.async();
        context.assertFalse(jedis.exists(QUEUE_REQUESTS));
        jedis.hset(QUEUE_REQUESTS, "some_other_queue", new JsonObject().encode());
        storage.removeQueueRequest("not_existing_queue").setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(jedis.exists(QUEUE_REQUESTS));
            context.assertTrue(jedis.hexists(QUEUE_REQUESTS, "some_other_queue"));
            async.complete();
        });
    }

    @Test
    public void testRemoveQueueRequest(TestContext context){
        Async async = context.async();
        context.assertFalse(jedis.exists(QUEUE_REQUESTS));
        jedis.hset(QUEUE_REQUESTS, "some_other_queue", new JsonObject().encode());
        jedis.hset(QUEUE_REQUESTS, "queue_1", new JsonObject().encode());

        context.assertTrue(jedis.hexists(QUEUE_REQUESTS, "queue_1"));
        storage.removeQueueRequest("queue_1").setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(jedis.exists(QUEUE_REQUESTS));
            context.assertTrue(jedis.hexists(QUEUE_REQUESTS, "some_other_queue"));
            context.assertFalse(jedis.hexists(QUEUE_REQUESTS, "queue_1"));
            async.complete();
        });
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

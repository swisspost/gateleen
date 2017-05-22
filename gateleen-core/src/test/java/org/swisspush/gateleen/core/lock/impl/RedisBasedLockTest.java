package org.swisspush.gateleen.core.lock.impl;

import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.redis.RedisClient;
import io.vertx.redis.RedisOptions;
import org.junit.*;
import org.junit.runner.RunWith;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

/**
 * Tests for the {@link RedisBasedLock} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class RedisBasedLockTest {

    private static Vertx vertx;
    private Jedis jedis;
    private static RedisBasedLock redisBasedLock;

    @org.junit.Rule
    public Timeout rule = Timeout.seconds(5);

    @BeforeClass
    public static void setupLock(){
        vertx = Vertx.vertx();
        redisBasedLock = new RedisBasedLock(RedisClient.create(vertx, new RedisOptions()));
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

    @After
    public void tearDown(){
        if(jedis != null){
            jedis.close();
        }
    }

    private String lockKey(String lock){
        return RedisBasedLock.STORAGE_PREFIX + lock;
    }

    @Test
    public void testAcquireLock(TestContext context){
        Async async = context.async();
        redisBasedLock.acquireLock("lock_1", "token_1", 500).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(event.result());
            context.assertTrue(jedis.exists(lockKey("lock_1")));
            context.assertEquals("token_1", jedis.get(lockKey("lock_1")));
            async.complete();
        });
    }

    @Test
    public void testAcquireLockAgain(TestContext context){
        Async async = context.async();
        redisBasedLock.acquireLock("lock_1", "token_1", 10000).setHandler(event -> {
            context.assertTrue(jedis.exists(lockKey("lock_1")));
            context.assertEquals("token_1", jedis.get(lockKey("lock_1")));
            context.assertTrue(event.succeeded());
            context.assertTrue(event.result());
            redisBasedLock.acquireLock("lock_1", "token_2", 15000).setHandler(event1 -> {
                context.assertTrue(jedis.exists(lockKey("lock_1")));
                context.assertEquals("token_1", jedis.get(lockKey("lock_1")));
                context.assertTrue(event1.succeeded());
                context.assertFalse(event1.result());
                async.complete();
            });
        });
    }
}

package org.swisspush.gateleen.queue;

import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;
import io.restassured.RestAssured;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;

import static org.awaitility.Awaitility.await;
import static io.restassured.RestAssured.*;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static org.awaitility.Durations.*;
import static org.hamcrest.CoreMatchers.*;

@RunWith(VertxUnitRunner.class)
public class QueueTest extends AbstractTest {

    public void init() {
        delete();

        RestAssured.requestSpecification.basePath(SERVER_ROOT + "/");

        // add a routing
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);
        rules = TestUtils.addRoutingRuleQueuing(rules);
        rules = TestUtils.addRoutingRuleCleanup(rules);
        TestUtils.putRoutingRules(rules);
    }

    @Test
    public void testPut(TestContext context) {
        Async async = context.async();
        init();

        with().body("{ \"foo\": \"bar1\" }").put("res");

        given().header("x-queue", "gateleen-test").body("{ \"foo\": \"bar2\" }").when().put("res").then().assertThat().statusCode(202);

        await().atMost(TWO_SECONDS).until(() -> get("res").then().extract().body().jsonPath().getString("foo"), equalTo("bar2"));

        async.complete();
    }

    @Test
    public void testQueueCleanupTest(TestContext context) {
        Async async = context.async();
        init();

        // Caution, this test takes 30 seconds :)
        put("queuing/locks/scheduler-queue-cleanup");

        try {
            // Create two queues
            given().header("x-queue", "gateleen-cleanup-test-1").body("{ \"foo\": \"bar1\" }").when().put("tests/gateleen/res").then().assertThat().statusCode(202);

            given().header("x-queue", "gateleen-cleanup-test-2").body("{ \"foo\": \"bar2\" }").when().put("tests/gateleen/res").then().assertThat().statusCode(202);

            // Create a blocked queue
            given().header("x-queue", "gateleen-cleanup-test-3").when().delete("tests/gateleen/unknown-resource").then().assertThat().statusCode(202);

            // Check that the queues are recorded in the set
            Long queueSize = jedis.zcard("redisques:queues");
            if (queueSize == null) {
                queueSize = 1000L;
            }
            final Long finalQueueSize = queueSize;

            assertFalse("redisques:queues has " + finalQueueSize + " items. Check whether the cleanup is still working!", finalQueueSize > 2000);

            Set<String> queues = jedis.zrange("redisques:queues", 0, finalQueueSize);

            assertTrue(queues.contains("gateleen-cleanup-test-1"));
            assertTrue(queues.contains("gateleen-cleanup-test-2"));
            assertTrue(queues.contains("gateleen-cleanup-test-3"));

            Double initialTs = jedis.zscore("redisques:queues", "gateleen-cleanup-test-3");

            // The first two one should disappear after cleanup
            await().atMost(ONE_MINUTE).until(() -> {
                // Cleanup
                get("cleanup");
                Set<String> queues1 = jedis.zrange("redisques:queues", 0, finalQueueSize);
                return !queues1.contains("gateleen-cleanup-test-1") &&
                        !queues1.contains("gateleen-cleanup-test-2") &&
                        queues1.contains("gateleen-cleanup-test-3");

            });

            // The timestamp of the blocked queue should have been updated by the cleanup
            assertTrue(jedis.zscore("redisques:queues", "gateleen-cleanup-test-3") > initialTs);

            // Clear the blocked queue
            jedis.del("redisques:queues:gateleen-cleanup-test-3");

        } finally {
            delete("queuing/locks/scheduler-queue-cleanup");
        }

        async.complete();
    }

    @Test
    public void testQueueLock(TestContext context) {
        Async async = context.async();
        init();

        // lock the queue
        when().put("queuing/locks/gateleen-lock-test").then().assertThat().statusCode(200);

        // put an item in the queue
        given().header("x-queue", "gateleen-lock-test").when().put("tests/gateleen/lock-resource");

        // check it is locked
        TestUtils.waitSomeTime(2);
        when().get("queuing/queues/").then().assertThat().body("queues", hasItem("gateleen-lock-test"));

        // check that the lock is in the list
        when().get("queuing/locks/").then().assertThat().body("locks", hasItem("gateleen-lock-test"));

        // check that the lock exists
        when().get("queuing/locks/gateleen-lock-test").then().assertThat().statusCode(200);

        // check that it is not lying
        when().get("queuing/locks/unexisting-lock").then().assertThat().statusCode(404);

        // remove the lock
        when().delete("queuing/locks/gateleen-lock-test").then().assertThat().statusCode(200);

        // put an item in the queue to ensure immediate flush
        given().header("x-queue", "gateleen-lock-test").when().put("tests/gateleen/lock-resource2");

        // check that the lock is no more the list
        when().get("queuing/locks/").then().assertThat().body("locks", not(hasItem("gateleen-lock-test")));

        // check that the lock no longer exists
        when().get("queuing/locks/gateleen-lock-test").then().assertThat().statusCode(404);

        // check that the queue has been emptied
        await().atMost(FIVE_SECONDS).until(() -> get("queuing/queues/gateleen-lock-test").then().extract().body().jsonPath().get("gateleen-lock-test.size()"),
                equalTo(0));

        async.complete();
    }

    @Test
    public void testQueueUpdate(TestContext context) {
        Async async = context.async();
        init();

        // lock the queue
        when().put("queuing/locks/gateleen-update-test").then().assertThat().statusCode(200);

        try {

            // clean the queue
            // when().delete("queuing/queues/gateleen-update-test").then().assertThat().statusCode(200);

            // put items in the queue
            given().header("x-queue", "gateleen-update-test").body("{ \"a\": 1}").when().put("tests/gateleen/update-resource1");

            given().header("x-queue", "gateleen-update-test").body("{ \"a\": 2}").when().put("tests/gateleen/update-resource2");

            given().header("x-queue", "gateleen-update-test").body("{ \"a\": 3}").when().put("tests/gateleen/update-resource3");

            when().get("queuing/queues/gateleen-update-test/0").then().assertThat().statusCode(200).body("uri", equalTo("/playground/server/tests/gateleen/update-resource1"));

            when().get("queuing/queues/gateleen-update-test/1").then().assertThat().statusCode(200).body("uri", equalTo("/playground/server/tests/gateleen/update-resource2"));

            when().get("queuing/queues/gateleen-update-test/2").then().assertThat().statusCode(200).body("uri", equalTo("/playground/server/tests/gateleen/update-resource3"));

            delete("queuing/queues/gateleen-update-test/1");

            when().get("queuing/queues/gateleen-update-test/1").then().assertThat().statusCode(200).body("uri", equalTo("/playground/server/tests/gateleen/update-resource3"));

            JsonObject req = new JsonObject(get("queuing/queues/gateleen-update-test/1").then().extract().body().asString());
            req.put("uri", "/playground/server/tests/gateleen/update-resource4");

            given().body(req.toString()).when().put("queuing/queues/gateleen-update-test/1").then().assertThat().statusCode(200);

            when().get("queuing/queues/gateleen-update-test/1").then().assertThat().statusCode(200).body("uri", equalTo("/playground/server/tests/gateleen/update-resource4"));
        } finally {

            // unlock the queue
            delete("queuing/locks/gateleen-update-test");
        }

        async.complete();
    }
}

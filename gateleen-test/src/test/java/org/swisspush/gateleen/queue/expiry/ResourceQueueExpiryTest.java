package org.swisspush.gateleen.queue.expiry;

import io.restassured.RestAssured;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static io.restassured.RestAssured.*;
import static org.awaitility.Durations.*;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;


/**
 * Test class for the expiration feature of
 * the resource queue.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
@RunWith(VertxUnitRunner.class)
public class ResourceQueueExpiryTest extends AbstractTest {

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath(SERVER_ROOT + "/");
    }

    private void initRoutingRules() {
        // add a routing
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);
        rules = TestUtils.addRoutingRuleQueuing(rules);
        TestUtils.putRoutingRules(rules);
    }

    /**
     * Checks if the GET request of the
     * given resource returns the wished body.
     * 
     * @param requestUrl
     * @param body
     */
    private void checkGETBodyWithAwait(final String requestUrl, final String body) {
        await().atMost(FIVE_SECONDS).until(() -> when().get(requestUrl).then().extract().body().asString(), equalTo(body));
    }

    /**
     * Tests if the expire works with the
     * queue feature of gateleen.
     */
    @Test
    public void testQueueExpiry(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        System.out.println("testQueueExpiry");

        RestAssured.basePath = "/server/";

        /*
         * Put something in the queue
         * and check if it processed.
         * ----
         */
        Map<String, String> headers = new HashMap<>();
        headers.put("x-queue", "gateleen-queue-expiry-test-pass");
        headers.put("x-expire-after", "3");

        String requestUrl = "tests/gateleen/queueexpiry/gateleen-pass-queue-test";
        String body = "{ \"name\" : \"gateleen-queue-unlocked-pass-test\" }";

        given().headers(headers).body(body).when().put(requestUrl);

        // check it is processed properly
        TestUtils.waitSomeTime(2);
        checkGETBodyWithAwait(requestUrl, body);

        // ----

        /*
         * Lock the queue and put
         * something in the queue.
         * After that wait a few seconds
         * till the expire time is over.
         * Then unlock the queue and
         * check if nothing is processed.
         */

        // put something to the queue - expire time 2 seconds
        // ----

        // lock the queue
        String lockDiscardRequestUrl = "queuing/locks/gateleen-queue-expiry-test-discard";
        given().put(lockDiscardRequestUrl);

        headers = new HashMap<>();
        headers.put("x-queue", "gateleen-queue-expiry-test-discard");
        headers.put("x-expire-after", "2");

        String discardedRequestUrl = "tests/gateleen/queueexpiry/gateleen-queue-expiry-test-discard";
        String discardedBody = "{ \"name\" : \"gateleen-queue-expiry-test-discard\" }";

        given().headers(headers).body(discardedBody).when().put(discardedRequestUrl);

        // ----

        // put something to the que - expire time 10 seconds
        // ----

        // lock the queue
        String lockPassedRequestUrl = "queuing/locks/gateleen-queue-expiry-test-pass";
        given().put(lockPassedRequestUrl);

        headers = new HashMap<>();
        headers.put("x-queue", "gateleen-queue-expiry-test-pass");
        headers.put("x-expire-after", "10");

        String passedRequestUrl = "tests/gateleen/queueexpiry/gateleen-queue-expiry-test-pass";
        String passedBody = "{ \"name\" : \"gateleen-queue-expiry-test-pass\" }";

        given().headers(headers).body(passedBody).when().put(passedRequestUrl);

        // ----

        // wait 2 seconds
        await().timeout(TWO_SECONDS);

        // check if the two items are in the queue
        when().get("queuing/queues/").then().assertThat().body("queues", hasItem("gateleen-queue-expiry-test-discard"));
        when().get("queuing/queues/").then().assertThat().body("queues", hasItem("gateleen-queue-expiry-test-pass"));

        // wait 5 seconds
        TestUtils.waitSomeTime(5);

        // remove the locks
        when().delete(lockDiscardRequestUrl).then().assertThat().statusCode(200);
        when().delete(lockPassedRequestUrl).then().assertThat().statusCode(200);

        // put items in the queue to ensure immediate flush
        headers = new HashMap<>();
        headers.put("x-queue", "gateleen-queue-expiry-test-discard");
        body = "{ \"name\" : \"gateleen-flush-queue\" }";
        given().headers(headers).body(body).when().put("tests/gateleen/queueexpiry/queue-flush-resource");

        headers = new HashMap<>();
        headers.put("x-queue", "gateleen-queue-expiry-test-pass");
        body = "{ \"name\" : \"gateleen-flush-queue\" }";
        given().headers(headers).body(body).when().put("tests/gateleen/queueexpiry/queue-flush-resource");

        // check if first request in queue was discarded
        when().get(discardedRequestUrl).then().assertThat().statusCode(404);

        // check if second request in queue passed
        when().get(passedRequestUrl).then().assertThat().statusCode(200);

        // ----

        async.complete();
    }

    @Test
    public void testQueueExpiryOverride_requestIsExpired_beforeRegularExpiryTime(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        System.out.println("testQueueExpiry");

        RestAssured.basePath = "/server/";

        // lock the queue
        String name = "gateleen-queue-expiry-override-test-one";
        String lockRequestUrl = "queuing/locks/" + name;
        given().put(lockRequestUrl);

        Map<String, String> headers = new HashMap<>();
        headers.put("x-queue", name);
        headers.put("x-expire-after", "10");
        headers.put("x-queue-expire-after", "5");

        // put something to the queue
        String discardedRequestUrl = "tests/gateleen/queueexpiry/" + name;
        String discardedBody = "{ \"name\" : \"" + name + "\" }";

        given().headers(headers).body(discardedBody).when().put(discardedRequestUrl);

        // wait 2 seconds
        await().timeout(TWO_SECONDS);

        // check if item is still in queue - yes
        when().get("queuing/queues/").then().assertThat().body("queues", hasItem(name));

        // wait 5 seconds
        TestUtils.waitSomeTime(5);

        // remove the locks and flush
        when().delete(lockRequestUrl).then().assertThat().statusCode(200);
        given().headers(headers).put("test/gateleen/queueexpiry/flush");

        // wait some seconds
        TestUtils.waitSomeTime(2);

        // check if resource was written (should be discared)
        when().get(discardedRequestUrl).then().assertThat().statusCode(404);

        async.complete();
    }


    @Test
    public void testQueueExpiryOverride_requestIsNotExpired_regularResourceExpiry(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        System.out.println("testQueueExpiry");

        RestAssured.basePath = "/server/";

        // lock the queue
        String name = "gateleen-queue-expiry-override-test-two";
        String lockRequestUrl = "queuing/locks/" + name;
        given().put(lockRequestUrl);

        Map<String, String> headers = new HashMap<>();
        headers.put("x-queue", name);
        headers.put("x-expire-after", "15");
        headers.put("x-queue-expire-after", "8");

        // put something to the queue
        String discardedRequestUrl = "tests/gateleen/queueexpiry/" + name;
        String discardedBody = "{ \"name\" : \"" + name + "\" }";

        given().headers(headers).body(discardedBody).when().put(discardedRequestUrl);

        // wait 2 seconds
        await().timeout(TWO_SECONDS);

        // check if item is still in queue - yes
        when().get("queuing/queues/").then().assertThat().body("queues", hasItem(name));

        // wait some seconds
        TestUtils.waitSomeTime(2);

        // remove the locks and flush
        when().delete(lockRequestUrl).then().assertThat().statusCode(200);
        given().headers(headers).put("test/gateleen/queueexpiry/flush");

        // wait some seconds
        TestUtils.waitSomeTime(2);

        // check if resource was written
        when().get(discardedRequestUrl).then().assertThat().statusCode(200);

        // wait some seconds
        TestUtils.waitSomeTime(5);

        // check if resource was written
        when().get(discardedRequestUrl).then().assertThat().statusCode(200);

        // wait some time till expiry
        await().atMost(TEN_SECONDS).until(() ->
                get(discardedRequestUrl).getStatusCode(),
                equalTo(404));

        async.complete();
    }
}

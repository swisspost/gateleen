package org.swisspush.gateleen.queue.expiry;

import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;
import com.jayway.awaitility.Duration;
import com.jayway.restassured.RestAssured;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;

import static com.jayway.awaitility.Awaitility.await;
import static com.jayway.restassured.RestAssured.*;
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

    /**
     * Checks if the GET request of the
     * given resource returns the wished body.
     * 
     * @param requestUrl
     * @param body
     */
    private void checkGETBodyWithAwait(final String requestUrl, final String body) {
        await().atMost(Duration.FIVE_SECONDS).until(() -> when().get(requestUrl).then().extract().body().asString(), equalTo(body));
    }

    /**
     * Tests if the expire works with the
     * queue feature of gateleen.
     */
    @Test
    public void testQueueExpiry(TestContext context) {
        Async async = context.async();
        delete();

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
        await().timeout(Duration.TWO_SECONDS);

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
}

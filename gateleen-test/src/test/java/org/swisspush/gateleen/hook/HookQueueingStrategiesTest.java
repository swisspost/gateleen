package org.swisspush.gateleen.hook;

import com.google.common.collect.ImmutableMap;
import io.restassured.RestAssured;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.awaitility.Awaitility;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;

import java.util.List;

import static io.restassured.RestAssured.*;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.hamcrest.collection.IsEmptyCollection.empty;

/**
 * Test class for the queueing strategies of the hooking feature.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class HookQueueingStrategiesTest extends AbstractTest {

    public void init() {
        delete();

        RestAssured.requestSpecification.basePath(SERVER_ROOT + "/");

        // add a routing
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);

        JsonObject queuing = TestUtils.createRoutingRule(ImmutableMap.of(
                "description",
                "Redisques API for push notifications",
                "url",
                "http://localhost:7015/queuing/$1",
                "timeout",
                120));

        rules = TestUtils.addRoutingRule(rules, AbstractTest.SERVER_ROOT + "/pushnotification/queuing/(.*)", queuing);

        TestUtils.putRoutingRules(rules);
    }

    @Test
    public void testDiscardPayloadQueueingStrategy(TestContext context) {
        Async async = context.async();
        init();

        String queueName = "listener-hook-http+push+super1+playground+server+tests+hooktest";

        // register hook
        String body = "{\"destination\":\"/playground/server/event/v1/channels/super1\",\"methods\":[\"PUT\"],\"expireAfter\":300,\"queueingStrategy\":{\"type\":\"discardPayload\"},\"fullUrl\":true,\"staticHeaders\":{\"x-sync\":true}}";
        with().body(body).put("tests/hooktest/_hooks/listeners/http/push/super1");

        // check if hook has been created
        when().get("hooks/v1/registrations/listeners/").then().assertThat().body("listeners", hasItem("http+push+super1+playground+server+tests+hooktest"));

        // check no queues exisiting yet
        when().get("pushnotification/queuing/queues/").then().assertThat().body("queues", empty());

        // make some changes to the hooked resource
        given().body("{ \"a\": 1}").when().put("tests/hooktest/resource_1").then().assertThat().statusCode(200);
        given().body("{ \"a\": 2}").when().put("tests/hooktest/resource_2").then().assertThat().statusCode(200);
        given().body("{ \"a\": 3}").when().put("tests/hooktest/resource_3").then().assertThat().statusCode(200);

        TestUtils.waitSomeTime(2);

        // check that the corresponding queue has been created with the correct count of queue items
        when().get("pushnotification/queuing/queues/")
                .then().assertThat()
                .body("queues", hasItem(queueName));
        given().urlEncodingEnabled(false).when().get("pushnotification/queuing/queues/" + queueName)
                .then().assertThat()
                .body(queueName, hasSize(3));

        // check that no queue items do have a payload
        String responseIndex0 = given().urlEncodingEnabled(false)
                .when().get("pushnotification/queuing/queues/" + queueName + "/0")
                .then().extract().response().asString();
        String responseIndex1 = given().urlEncodingEnabled(false)
                .when().get("pushnotification/queuing/queues/" + queueName + "/1")
                .then().extract().response().asString();
        String responseIndex2 = given().urlEncodingEnabled(false)
                .when().get("pushnotification/queuing/queues/" + queueName + "/2")
                .then().extract().response().asString();

        validateDiscardPayloadQueueItem(responseIndex0, context);
        validateDiscardPayloadQueueItem(responseIndex1, context);
        validateDiscardPayloadQueueItem(responseIndex2, context);

        async.complete();
    }

    private void validateDiscardPayloadQueueItem(String responseStr, TestContext context){
        JsonObject response = new JsonObject(responseStr);
        context.assertEquals("", response.getString("payloadString"));

        boolean headersContainContentLengthZero = false;
        for (Object headerEntry : response.getJsonArray("headers").getList()) {
            List header = (List) headerEntry;
            if("Content-Length".equals(header.get(0))){
                if("0".equals(header.get(1))){
                    headersContainContentLengthZero = true;
                }
            }
        }

        context.assertTrue(headersContainContentLengthZero, "headers should contain a 'Content-Length' value of 0");
    }

    @Test
    public void testReducedPropagationQueueingStrategy(TestContext context) {
        Async async = context.async();
        init();

        String queueName = "listener-hook-http+push+super3+playground+server+tests+hooktest";
        String managerQueue = "manager_" + queueName;

        // register hook
        String body = "{\"destination\":\"/playground/server/event/v1/channels/super3\",\"methods\":[\"PUT\"],\"expireAfter\":300,\"queueingStrategy\":{\"type\":\"reducedPropagation\",\"intervalMs\":5000},\"fullUrl\":true,\"staticHeaders\":{\"x-sync\":true}}";
        with().body(body).put("tests/hooktest/_hooks/listeners/http/push/super3");

        // check if hook has been created
        when().get("hooks/v1/registrations/listeners/").then().assertThat().body("listeners", hasItem("http+push+super3+playground+server+tests+hooktest"));

        // check no queues exisiting yet
        when().get("pushnotification/queuing/queues/").then().assertThat().body("queues", empty());

        // make some changes to the hooked resource
        given().body("{ \"a\": 1}").when().put("tests/hooktest/resource_1").then().assertThat().statusCode(200);
        given().body("{ \"a\": 2}").when().put("tests/hooktest/resource_2").then().assertThat().statusCode(200);
        given().body("{ \"a\": 3}").when().put("tests/hooktest/resource_3").then().assertThat().statusCode(200);

        TestUtils.waitSomeTime(2);

        // check that the corresponding queue has been created and that it's locked
        when().get("pushnotification/queuing/queues/")
                .then().assertThat()
                .body("queues", hasItem(queueName))
                .body("queues", not(hasItem(managerQueue)));
        given().urlEncodingEnabled(false).when().get("pushnotification/queuing/queues/" + queueName)
                .then().assertThat()
                .body(queueName, hasSize(3));
        when().get("pushnotification/queuing/locks/")
                .then().assertThat()
                .body("locks", hasItem(queueName))
                .body("locks", not(hasItem(managerQueue)));

        // after 5s an unlocked manager queue with a single queue item should exist and the original queue should be empty
        // and its lock should have been deleted
        Awaitility.await().untilAsserted(() -> when().get("pushnotification/queuing/queues/").then().assertThat().body("queues", hasItem(managerQueue)));
        given().urlEncodingEnabled(false).when().get("pushnotification/queuing/queues/" + managerQueue)
                .then().assertThat()
                .body(managerQueue, hasSize(1));
        given().urlEncodingEnabled(false).when().get("pushnotification/queuing/queues/" + queueName)
                .then().assertThat()
                .body(queueName, empty());

        when().get("pushnotification/queuing/locks/")
                .then().assertThat()
                .body("locks", not(hasItem(queueName)))
                .body("locks", not(hasItem(managerQueue)));


        // make some more changes
        given().body("{ \"a\": 4}").when().put("tests/hooktest/resource_4").then().assertThat().statusCode(200);
        given().body("{ \"a\": 5}").when().put("tests/hooktest/resource_5").then().assertThat().statusCode(200);

        TestUtils.waitSomeTime(2);

        when().get("pushnotification/queuing/queues/")
                .then().assertThat()
                .body("queues", hasItem(queueName))
                .body("queues", hasItem(managerQueue));
        given().urlEncodingEnabled(false).when().get("pushnotification/queuing/queues/" + queueName)
                .then().assertThat()
                .body(queueName, hasSize(2));
        when().get("pushnotification/queuing/locks/")
                .then().assertThat()
                .body("locks", hasItem(queueName))
                .body("locks", not(hasItem(managerQueue)));

        // after another 5s the unlocked manager queue with a single queue item should still exist and the original queue should be empty
        // and its lock should have been deleted
        TestUtils.waitSomeTime(5);
        Awaitility.await().untilAsserted(() -> when().get("pushnotification/queuing/queues/").then().assertThat().body("queues", hasItem(managerQueue)));
        given().urlEncodingEnabled(false).when().get("pushnotification/queuing/queues/" + managerQueue)
                .then().assertThat()
                .body(managerQueue, hasSize(1));
        given().urlEncodingEnabled(false).when().get("pushnotification/queuing/queues/" + queueName)
                .then().assertThat()
                .body(queueName, empty());

        when().get("pushnotification/queuing/locks/")
                .then().assertThat()
                .body("locks", not(hasItem(queueName)))
                .body("locks", not(hasItem(managerQueue)));

        async.complete();
    }
}

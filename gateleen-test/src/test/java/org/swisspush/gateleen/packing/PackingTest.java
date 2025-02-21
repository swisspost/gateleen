package org.swisspush.gateleen.packing;

import io.restassured.RestAssured;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.*;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.equalTo;

/**
 * Tests the packing functionality of gateleen
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class PackingTest extends AbstractTest {

    private static final String KEY = "key";

    private Buffer validSinglePackedRequest = Buffer.buffer("{\n" +
            "  \"requests\": [\n" +
            "    {\n" +
            "      \"uri\": \"/playground/server/tests/sub/res1\",\n" +
            "      \"method\": \"PUT\",\n" +
            "      \"payload\": {\n" +
            "        \"key\": 1,\n" +
            "        \"key2\": [1,2,3]\n" +
            "      },\n" +
            "      \"headers\": [[\"x-foo\", \"bar\"], [\"x-bar\", \"foo\"]]\n" +
            "    }\n" +
            "  ]\n" +
            "}");

    private Buffer invalidSinglePackedRequest = Buffer.buffer("{\n" +
            "  \"requests\": [\n" +
            "    {\n" +
            "      \"uri\": \"/playground/server/tests/sub/res1\",\n" +
            "      \"payload\": {\n" +
            "        \"key\": 1,\n" +
            "        \"key2\": [1,2,3]\n" +
            "      },\n" +
            "      \"headers\": [[\"x-foo\", \"bar\"], [\"x-bar\", \"foo\"]]\n" +
            "    }\n" +
            "  ]\n" +
            "}");

    @Test
    public void testInvalidPackedRequest(TestContext context) {
        Async async = context.async();
        init();

        given().header("x-packed", "true").body(invalidSinglePackedRequest.toString()).when().put("myrequest").then().assertThat().statusCode(400);

        async.complete();
    }

    @Test
    public void testEmptyPackedRequest(TestContext context) {
        Async async = context.async();
        init();

        given().header("x-packed", "true").body("{\"requests\": []}").when().put("myrequest").then().assertThat().statusCode(200);

        async.complete();
    }

    @Test
    public void testPackedRequest(TestContext context) {
        Async async = context.async();
        init();

        when().get("/tests/sub/res1").then().assertThat().statusCode(404);

        given().header("x-packed", "true").body(validSinglePackedRequest.toString()).when().put("/tests/packed/myrequest").then().assertThat().statusCode(200);

        await().atMost(2, TimeUnit.SECONDS).until(() -> get("/tests/sub/res1").statusCode(), equalTo(200));

        async.complete();
    }

    @Test
    public void testPackedRequestPredefinedQueue(TestContext context) {
        Async async = context.async();
        init();

        String queuName = "queue-" + System.currentTimeMillis();

        JsonObject payload = new JsonObject();
        JsonArray requests = new JsonArray();
        payload.put("requests", requests);
        for (int i = 0; i < 5; i++) {
            JsonObject request = new JsonObject();
            request.put("uri", "/playground/server/tests/sub/res" + i);
            request.put("method", "PUT");
            request.put("payload", new JsonObject().put(KEY, i));
            request.put("headers", new JsonArray().add(new JsonArray().add("x-queue").add(queuName)));
            requests.add(request);
        }

        // lock the queue
        when().put("queuing/locks/" + queuName).then().assertThat().statusCode(200);

        // queue should not exist yet
        List<String> queues = get("queuing/queues/").then().extract().body().jsonPath().getList("queues");
        context.assertFalse(queues.contains(queuName));

        when().get("/tests/sub/res0").then().assertThat().statusCode(404);
        when().get("/tests/sub/res1").then().assertThat().statusCode(404);
        when().get("/tests/sub/res1").then().assertThat().statusCode(404);
        when().get("/tests/sub/res2").then().assertThat().statusCode(404);
        when().get("/tests/sub/res3").then().assertThat().statusCode(404);

        given().header("x-packed", "true").body(payload.toString()).when().put("/tests/packed/myrequest").then().assertThat().statusCode(200);

        TestUtils.waitSomeTime(2);

        // queue should exist
        queues = get("queuing/queues/").then().extract().body().jsonPath().getList("queues");
        context.assertTrue(queues.contains(queuName));

        // queue should not have been processed yet
        when().get("/tests/sub/res0").then().assertThat().statusCode(404);
        when().get("/tests/sub/res1").then().assertThat().statusCode(404);
        when().get("/tests/sub/res1").then().assertThat().statusCode(404);
        when().get("/tests/sub/res2").then().assertThat().statusCode(404);
        when().get("/tests/sub/res3").then().assertThat().statusCode(404);

        when().get("queuing/queues/" + queuName + "?count=true").then().assertThat().statusCode(200).body("count", equalTo(5));

        async.complete();
    }

    @Test
    public void testPackedRequestMany(TestContext context) {
        Async async = context.async();
        init();

        JsonObject payload = new JsonObject();
        JsonArray requests = new JsonArray();
        payload.put("requests", requests);
        for (int i = 0; i < 40; i++) {
            JsonObject request = new JsonObject();
            request.put("uri", "/playground/server/tests/sub/res" + i);
            request.put("method", "PUT");
            request.put("payload", new JsonObject().put(KEY, i));
            requests.add(request);
        }

        // assert resources do not exist
        when().get("/tests/sub/res1").then().assertThat().statusCode(404);
        when().get("/tests/sub/res23").then().assertThat().statusCode(404);
        when().get("/tests/sub/res38").then().assertThat().statusCode(404);

        given().header("x-packed", "true").body(payload.encode()).when().put("/tests/packed/myrequest").then().assertThat().statusCode(200);

        TestUtils.waitSomeTime(2);
        when().get("/tests/sub/").then().assertThat().statusCode(200);

        List<String> collection = get("/tests/sub/").then().extract().body().jsonPath().getList("sub");
        context.assertEquals(40, collection.size());

        // assert resources do now exist
        when().get("/tests/sub/res1").then().assertThat().statusCode(200).body(KEY, equalTo(1));
        when().get("/tests/sub/res23").then().assertThat().statusCode(200).body(KEY, equalTo(23));
        when().get("/tests/sub/res38").then().assertThat().statusCode(200).body(KEY, equalTo(38));

        async.complete();
    }

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
}

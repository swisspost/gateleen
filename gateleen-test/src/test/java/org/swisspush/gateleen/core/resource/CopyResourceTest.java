package org.swisspush.gateleen.core.resource;

import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;
import com.jayway.awaitility.Duration;
import com.jayway.restassured.RestAssured;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import static com.jayway.awaitility.Awaitility.await;
import static com.jayway.restassured.RestAssured.*;
import static org.hamcrest.CoreMatchers.equalTo;

/**
 * Test class for testing the CopyResourceHandler.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
@RunWith(VertxUnitRunner.class)
public class CopyResourceTest extends AbstractTest {
    private String copyUrl;
    private String base;
    private static final int BAD_REQUEST = 400;
    private static final int SUCESSFUL_REQUEST = 200;
    private static final int NOT_FOUND = 404;

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        base = SERVER_ROOT + "/tests/";
        RestAssured.requestSpecification.basePath(base);
    }

    public void init() {
        delete();

        // add a routing
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);
        rules = TestUtils.addRoutingRuleHooks(rules);
        TestUtils.putRoutingRules(rules);

        copyUrl = "http://localhost:" + MAIN_PORT + SERVER_ROOT + "/v1/copy";

        createTestResources();
    }

    public void createTestResources() {
        with().body("{ \"value\" : \"test\" }").put("copyresource/input/test1");
        with().body("{ \"value\" : \"test2\" }").put("copyresource/input/test2");
        with().body("{ \"value\" : \"test2\" }").put("copyresource/input/test3");
    }

    /**
     * Checks if trying to copy a collection is handeld
     * properly by the error handler.
     */
    @Test
    public void testCopyCollection(TestContext context) {
        Async async = context.async();
        init();

        // source & destination are collection
        with().body(createCopyTaskBody(base + "copyresource/input/", base + "copyresource/output/", null)).post(copyUrl).then().assertThat().statusCode(BAD_REQUEST);

        // source is a collection
        with().body(createCopyTaskBody(base + "copyresource/input/", base + "copyresource/output/test1", null)).post(copyUrl).then().assertThat().statusCode(BAD_REQUEST);

        // destination is collection
        with().body(createCopyTaskBody(base + "copyresource/input/test1", base + "copyresource/output/", null)).post(copyUrl).then().assertThat().statusCode(BAD_REQUEST);

        async.complete();
    }

    /**
     * Checks if the error handling for a not available
     * resource is done correctly.
     */
    @Test
    public void testCopyUnavailableResource(TestContext context) {
        Async async = context.async();
        init();

        with().body(createCopyTaskBody(base + "copyresource/input/test4", base + "copyresource/output/test4", null)).post(copyUrl).then().assertThat().statusCode(NOT_FOUND);

        async.complete();
    }

    /**
     * Checks if headers passed from the original POST
     * request are handled correctly.
     */
    @Test
    public void testPassedHeadersBehaviour(TestContext context) {
        Async async = context.async();
        init();

        // copy test2 - destination should disapear after 5 seconds
        with().body(createCopyTaskBody(base + "copyresource/input/test2", base + "copyresource/output/test2", null)).header("x-expire-after", "5").post(copyUrl)
                .then().assertThat().statusCode(SUCESSFUL_REQUEST);

        // destination should be available
        checkGETStatusWithAwait("copyresource/output/test2", SUCESSFUL_REQUEST);

        // wait 5 seconds
        TestUtils.waitSomeTime(5);

        // destination should no longer be available
        get("copyresource/output/test2").then().assertThat().statusCode(NOT_FOUND);

        async.complete();
    }

    /**
     * Checks if the static headers are applied
     * correctly.
     */
    @Test
    public void testStaticHeadersBehaviour(TestContext context) {
        Async async = context.async();
        init();

        // create static headers
        Map<String, String> headers = new HashMap<>();
        headers.put("x-expire-after", "5");

        // copy test3
        with().body(createCopyTaskBody(base + "copyresource/input/test3", base + "copyresource/output/test3", headers)).post(copyUrl)
                .then().assertThat().statusCode(SUCESSFUL_REQUEST);

        // destination should be available
        checkGETStatusWithAwait("copyresource/output/test3", SUCESSFUL_REQUEST);

        // wait 5 seconds
        TestUtils.waitSomeTime(5);

        // destination should no longer be available
        get("copyresource/output/test3").then().assertThat().statusCode(NOT_FOUND);

        async.complete();
    }

    /**
     * Checks if trying to copy a valid resource is handled
     * properly.
     */
    @Test
    public void testCopyValidResource(TestContext context) {
        Async async = context.async();
        init();

        // copy test1
        with().body(createCopyTaskBody(base + "copyresource/input/test1", base + "copyresource/output/test1", null)).post(copyUrl)
                .then().assertThat().statusCode(SUCESSFUL_REQUEST);

        // check if copied resource is identical with source resource
        context.assertEquals(get("copyresource/input/test1").asString(), get("copyresource/output/test1").asString());

        async.complete();
    }

    public String createCopyTaskBody(String source, String destination, Map<String, String> staticHeaders) {
        StringBuilder body = new StringBuilder();
        body.append("{");
        body.append("\"source\" : \"").append(source).append("\", ");
        body.append("\"destination\" : \"").append(destination).append("\"");

        if (staticHeaders != null && !staticHeaders.isEmpty()) {
            body.append(",");
            body.append("\"staticHeaders\" : {");

            StringBuilder headers = new StringBuilder();
            for (Entry<String, String> entry : staticHeaders.entrySet()) {
                if (headers.length() > 0) {
                    headers.append(", ");
                }

                headers.append("\"").append(entry.getKey()).append("\" : ");
                headers.append("\"").append(entry.getValue()).append("\"");
            }

            body.append(headers);

            body.append("}");
        }

        body.append("}");

        return body.toString();
    }

    private void checkGETStatusWithAwait(final String requestUrl, final int statusCode) {
        await().atMost(Duration.TWO_SECONDS).until(() -> when().get(requestUrl).getStatusCode(), equalTo(statusCode));
    }
}

package org.swisspush.gateleen.routing;

import com.google.common.collect.ImmutableMap;
import io.restassured.RestAssured;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;

import static io.restassured.RestAssured.*;
import static io.restassured.RestAssured.delete;
import static org.hamcrest.core.StringContains.containsString;

/**
 * Tests for the {@link CustomHttpResponseHandler} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class CustomHttpResponseHandlerTest extends AbstractTest {

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath(SERVER_ROOT);
    }

    @Test
    public void testCustomHttpResponses(TestContext context) {
        Async async = context.async();
        delete();

        // add a routing
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);
        TestUtils.putRoutingRules(rules);

        // PUT/GET should return status code 200
        with().body("{ \"foo\": \"bar\" }").put("/resources/res_1").then().assertThat().statusCode(200);
        when().get("/resources/res_1").then().assertThat().statusCode(200);

        // Add routing rule to respond with custom status code 503
        final JsonObject statuscode503RoutingRule = createRespondWithCustomStatuscodeRoutingRule("503");
        String ruleName = SERVER_ROOT + "/resources/(.*)";
        rules = TestUtils.addRoutingRule(rules, ruleName, statuscode503RoutingRule);
        TestUtils.putRoutingRules(rules);

        // GET should now return status code 503
        when().get("/resources/res_1").then().assertThat().statusCode(503);

        async.complete();
    }

    @Test
    public void testCustomHttpResponsesWithInvalidConfiguration(TestContext context) {
        Async async = context.async();
        delete();

        // add a routing
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);
        TestUtils.putRoutingRules(rules);

        // PUT/GET should return status code 200
        with().body("{ \"foo\": \"bar\" }").put("/resources/res_1").then().assertThat().statusCode(200);
        when().get("/resources/res_1").then().assertThat().statusCode(200);

        // Add routing rule to respond with an invalid custom status code configuration
        final JsonObject statuscodeInvalidRoutingRule = createRespondWithCustomStatuscodeRoutingRule("not_a_number");
        String ruleName = SERVER_ROOT + "/resources/(.*)";
        rules = TestUtils.addRoutingRule(rules, ruleName, statuscodeInvalidRoutingRule);
        TestUtils.putRoutingRules(rules);

        // GET should now return status code 400
        when().get("/resources/res_1").then().assertThat()
                .statusCode(400)
                .body(containsString("400 Bad Request: missing, wrong or non-numeric status-code in request URL"));

        async.complete();
    }

    @Test
    public void testCustomHttpResponsesWithUnknownStatuscode(TestContext context) {
        Async async = context.async();
        delete();

        // add a routing
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);
        TestUtils.putRoutingRules(rules);

        // PUT/GET should return status code 200
        with().body("{ \"foo\": \"bar\" }").put("/resources/res_1").then().assertThat().statusCode(200);
        when().get("/resources/res_1").then().assertThat().statusCode(200);

        // Add routing rule to respond with unknown custom status code 999
        final JsonObject statuscode1234RoutingRule = createRespondWithCustomStatuscodeRoutingRule("999");
        String ruleName = SERVER_ROOT + "/resources/(.*)";
        rules = TestUtils.addRoutingRule(rules, ruleName, statuscode1234RoutingRule);
        TestUtils.putRoutingRules(rules);

        // GET should now return status code 1234
        when().get("/resources/res_1").then().assertThat()
                .statusCode(999)
                .body(containsString("999 Unknown Status (999)"));

        async.complete();
    }

    private JsonObject createRespondWithCustomStatuscodeRoutingRule(String statusCode) {
        return TestUtils.createRoutingRule(ImmutableMap.of(
                "description",
                "Respond every request with status code " + statusCode,
                "path",
                SERVER_ROOT + "/return-with-status-code/" + statusCode));
    }
}

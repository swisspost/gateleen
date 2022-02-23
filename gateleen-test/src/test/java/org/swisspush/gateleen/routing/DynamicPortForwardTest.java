package org.swisspush.gateleen.routing;

import com.google.common.collect.ImmutableMap;
import io.restassured.RestAssured;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;

import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.containsString;

/**
 * Test class for dynamic ports in routing rules.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class DynamicPortForwardTest extends AbstractTest {

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath(SERVER_ROOT);
    }

    @Test
    public void testPUTValidRoutingRule(TestContext context) {
        Async async = context.async();

        JsonObject rules = new JsonObject();
        final JsonObject wildcardRoutingRule = createForwarderRoutingRule("$1");
        String TEST_RULE_NAME = SERVER_ROOT + "/tests/gateleen/(.*)/res/(.*)";
        rules = TestUtils.addRoutingRule(rules, TEST_RULE_NAME, wildcardRoutingRule);

        given().body(rules.toString()).put("/admin/v1/routing/rules").then().assertThat().statusCode(200);

        async.complete();
    }

    @Test
    public void testPUTInvalidRoutingRule(TestContext context) {
        Async async = context.async();

        JsonObject rules = new JsonObject();
        final JsonObject wildcardRoutingRule = createForwarderRoutingRule("$a99");
        String TEST_RULE_NAME = SERVER_ROOT + "/tests/gateleen/(.*)/res/(.*)";
        rules = TestUtils.addRoutingRule(rules, TEST_RULE_NAME, wildcardRoutingRule);

        given().body(rules.toString()).put("/admin/v1/routing/rules").then().assertThat()
                .statusCode(400)
                .body(containsString("Invalid url for pattern /playground/server/tests/gateleen/(.*)/res/(.*): " +
                        "http://localhost:$a99/dummy/tests/gateleen/$2"));

        async.complete();
    }

    @Test
    public void testValidPortWildcard(TestContext context) {
        Async async = context.async();

        JsonObject rules = new JsonObject();
        final JsonObject wildcardRoutingRule = createForwarderRoutingRule("$1");
        String TEST_RULE_NAME = SERVER_ROOT + "/tests/gateleen/(.*)/res/(.*)";
        rules = TestUtils.addRoutingRule(rules, TEST_RULE_NAME, wildcardRoutingRule);
        TestUtils.putRoutingRules(rules);

        // result should be 404 because forwarding is correct but no data on this path
        when().get("/tests/gateleen/" + AbstractTest.MAIN_PORT + "/res/abc").then().assertThat().statusCode(404);

        async.complete();
    }


    @Test
    public void testValidPortWildcardServiceUnavailable(TestContext context) {
        Async async = context.async();

        JsonObject rules = new JsonObject();
        final JsonObject wildcardRoutingRule = createForwarderRoutingRule("$1");
        String TEST_RULE_NAME = SERVER_ROOT + "/tests/gateleen/(.*)/res/(.*)";
        rules = TestUtils.addRoutingRule(rules, TEST_RULE_NAME, wildcardRoutingRule);
        TestUtils.putRoutingRules(rules);

        // result should be 503 because forwarding is correct but nothing runs on this port
        when().get("/tests/gateleen/1234/res/abc").then().assertThat().statusCode(503);

        async.complete();
    }

    @Test
    public void testPortWildcardIsNotANumber(TestContext context) {
        Async async = context.async();

        JsonObject rules = new JsonObject();
        final JsonObject wildcardRoutingRule = createForwarderRoutingRule("$1");
        String TEST_RULE_NAME = SERVER_ROOT + "/tests/gateleen/(.*)/res/(.*)";
        rules = TestUtils.addRoutingRule(rules, TEST_RULE_NAME, wildcardRoutingRule);
        TestUtils.putRoutingRules(rules);

        // result should be 500 because 'not_a_number' cannot be used as port
        when().get("/tests/gateleen/not_a_number/res/abc").then().assertThat().statusCode(500);

        async.complete();
    }

    @Test
    public void testPortWildcardOutOfRange(TestContext context) {
        Async async = context.async();

        JsonObject rules = new JsonObject();
        final JsonObject wildcardRoutingRule = createForwarderRoutingRule("$99");
        String TEST_RULE_NAME = SERVER_ROOT + "/tests/gateleen/(.*)/res/(.*)";
        rules = TestUtils.addRoutingRule(rules, TEST_RULE_NAME, wildcardRoutingRule);
        TestUtils.putRoutingRules(rules);

        // result should be 500 because no regex group $99 can be found
        when().get("/tests/gateleen/not_a_number/res/abc").then().assertThat().statusCode(500);

        async.complete();
    }

    private JsonObject createForwarderRoutingRule(String wildcard) {
        return TestUtils.createRoutingRule(ImmutableMap.of(
                "url",
                "http://localhost:" + wildcard + "/dummy/tests/gateleen/$2"));
    }
}

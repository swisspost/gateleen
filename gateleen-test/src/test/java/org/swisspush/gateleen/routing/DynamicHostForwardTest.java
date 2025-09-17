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

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.containsString;

@RunWith(VertxUnitRunner.class)
public class DynamicHostForwardTest extends AbstractTest {

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
        final JsonObject wildcardRoutingRule = createForwarderRoutingHostRule("$1", "$2");
        String TEST_RULE_NAME = SERVER_ROOT + "/tests/gateleen/(.*)/res/(.*)/more/(.*)";
        rules = TestUtils.addRoutingRule(rules, TEST_RULE_NAME, wildcardRoutingRule);

        given().body(rules.toString()).put("/admin/v1/routing/rules").then().assertThat().statusCode(200);

        async.complete();
    }

    @Test
    public void testPUTInvalidRoutingRule(TestContext context) {
        Async async = context.async();

        JsonObject rules = new JsonObject();
        final JsonObject wildcardRoutingRule = createForwarderRoutingHostRule("$a99", "$b99");
        String TEST_RULE_NAME = SERVER_ROOT + "/tests/gateleen/(.*)/res/(.*)/more/(.*)";
        rules = TestUtils.addRoutingRule(rules, TEST_RULE_NAME, wildcardRoutingRule);

        given().body(rules.toString()).put("/admin/v1/routing/rules").then().assertThat()
                .statusCode(400)
                .body(containsString("Invalid url for pattern /playground/server/tests/gateleen/(.*)/res/(.*)/more/(.*): " +
                        "http://$a99.host:$b99/dummy/tests/gateleen/$3"));

        async.complete();
    }


    @Test
    public void testValidHostWildcard(TestContext context) {
        Async async = context.async();

        JsonObject rules = new JsonObject();
        final JsonObject wildcardRoutingRule = createForwarderRoutingHostRule("$1", "$2");
        String TEST_RULE_NAME = SERVER_ROOT + "/tests/gateleen/(.*)/res/(.*)/more/(.*)";
        rules = TestUtils.addRoutingRule(rules, TEST_RULE_NAME, wildcardRoutingRule);
        TestUtils.putRoutingRules(rules);

        when().get("/tests/gateleen/www/res/abc").then().assertThat().statusCode(404);

        async.complete();
    }

    private JsonObject createForwarderRoutingHostRule(String wildcardHost, String wildcardPort) {
        return TestUtils.createRoutingRule(ImmutableMap.of(
                "url",
                "http://" + wildcardHost +".host:" + wildcardPort + "/dummy/tests/gateleen/$3"));
    }
}

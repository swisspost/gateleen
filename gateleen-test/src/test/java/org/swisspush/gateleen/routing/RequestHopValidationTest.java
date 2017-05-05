package org.swisspush.gateleen.routing;

import com.google.common.collect.ImmutableMap;
import com.jayway.restassured.RestAssured;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;

import static com.jayway.restassured.RestAssured.*;
import static org.hamcrest.CoreMatchers.*;
import static org.swisspush.gateleen.core.util.HttpRequestHeader.X_HOPS;

/**
 * Tests for the x-hops requests header validation feature.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class RequestHopValidationTest extends AbstractTest {

    private static final String ROUTING_CONFIG = "/admin/v1/routing/config";

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath(SERVER_ROOT);
    }

    @Test
    public void testRequestHopValidationLimitNotYetReached(TestContext context) {
        Async async = context.async();
        delete();

        // add a routing
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);
        rules = setupLoopingRules(rules);
        TestUtils.putRoutingRules(rules);

        // configure request hops limit to 10
        with().body("{\"request.hops.limit\":10}").put(ROUTING_CONFIG).then().assertThat().statusCode(200);
        validateRoutingConfig(true, 10);

        // prepare test data
        with().body("{\"someKey\":"+System.currentTimeMillis()+"}").put("/loop/4/resource").then().assertThat().statusCode(200);
        when().get("/loop/4/resource").then().assertThat().statusCode(200);

        // get the test data via looping rules
        when().get("/loop/1/resource").then().assertThat().statusCode(200);

        async.complete();
    }

    @Test
    public void testRequestHopValidationLimitExceeded(TestContext context) {
        Async async = context.async();
        delete();

        // add a routing
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);
        rules = setupLoopingRules(rules);
        TestUtils.putRoutingRules(rules);

        // configure request hops limit to 2
        with().body("{\"request.hops.limit\":2}").put(ROUTING_CONFIG).then().assertThat().statusCode(200);
        validateRoutingConfig(true, 2);

        // prepare test data
        with().body("{\"someKey\":"+System.currentTimeMillis()+"}").put("/loop/4/resource").then().assertThat().statusCode(200);
        when().get("/loop/4/resource").then().assertThat().statusCode(200);

        // get the test data via looping rules
        when().get("/loop/1/resource").then().assertThat()
                .statusCode(500)
                .statusLine(containsString("Request hops limit exceeded"))
                .body(containsString(buildLimitExceededMessage(2)));
        when().get("/loop/2/resource").then().assertThat()
                .statusCode(500)
                .statusLine(containsString("Request hops limit exceeded"))
                .body(containsString(buildLimitExceededMessage(2)));
        when().get("/loop/3/resource").then().assertThat()
                .statusCode(200)
                .body(not(containsString(buildLimitExceededMessage(2))));

        async.complete();
    }

    @Test
    public void testRequestHopValidationEndlessLoopingRule(TestContext context) {
        Async async = context.async();
        delete();

        // add a routing
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);
        rules = setupLoopingRules(rules);
        TestUtils.putRoutingRules(rules);

        // configure request hops limit to 50
        with().body("{\"request.hops.limit\":50}").put(ROUTING_CONFIG).then().assertThat().statusCode(200);
        validateRoutingConfig(true, 50);

        // get the test data via looping rules
        when().get("/looping/someresource").then().assertThat()
                .statusCode(500)
                .statusLine(containsString("Request hops limit exceeded"))
                .body(containsString(buildLimitExceededMessage(50)));

        async.complete();
    }

    @Test
    public void testRequestHopValidationDeleteConfiguration(TestContext context) {
        Async async = context.async();
        delete();

        // add a routing
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);
        rules = setupLoopingRules(rules);
        TestUtils.putRoutingRules(rules);

        // configure request hops limit to 2
        with().body("{\"request.hops.limit\":2}").put(ROUTING_CONFIG).then().assertThat().statusCode(200);
        validateRoutingConfig(true, 2);

        // prepare test data
        with().body("{\"someKey\":"+System.currentTimeMillis()+"}").put("/loop/4/resource").then().assertThat().statusCode(200);
        when().get("/loop/4/resource").then().assertThat().statusCode(200);

        // get the test data via looping rules
        when().get("/loop/1/resource").then().assertThat()
                .statusCode(500)
                .statusLine(containsString("Request hops limit exceeded"))
                .body(containsString(buildLimitExceededMessage(2)));
        when().get("/loop/2/resource").then().assertThat()
                .statusCode(500)
                .statusLine(containsString("Request hops limit exceeded"))
                .body(containsString(buildLimitExceededMessage(2)));
        when().get("/loop/3/resource").then().assertThat()
                .statusCode(200)
                .body(not(containsString(buildLimitExceededMessage(2))));

        // delete the routing config. the limit should then not be valid anymore
        delete(ROUTING_CONFIG);
        validateRoutingConfig(false, 0);

        when().get("/loop/1/resource").then().assertThat()
                .statusCode(200)
                .body(not(containsString(buildLimitExceededMessage(2))));
        when().get("/loop/2/resource").then().assertThat()
                .statusCode(200)
                .body(not(containsString(buildLimitExceededMessage(2))));
        when().get("/loop/3/resource").then().assertThat()
                .statusCode(200)
                .body(not(containsString(buildLimitExceededMessage(2))));

        // configure request hops limit to 2
        with().body("{\"request.hops.limit\":2}").put(ROUTING_CONFIG).then().assertThat().statusCode(200);
        validateRoutingConfig(true, 2);

        when().get("/loop/1/resource").then().assertThat()
                .statusCode(500)
                .statusLine(containsString("Request hops limit exceeded"))
                .body(containsString(buildLimitExceededMessage(2)));
        when().get("/loop/2/resource").then().assertThat()
                .statusCode(500)
                .statusLine(containsString("Request hops limit exceeded"))
                .body(containsString(buildLimitExceededMessage(2)));
        when().get("/loop/3/resource").then().assertThat()
                .statusCode(200)
                .body(not(containsString(buildLimitExceededMessage(2))));

        async.complete();
    }

    @Test
    public void testRequestHopValidationWithLimitZero(TestContext context) {
        Async async = context.async();
        delete();

        // add a routing
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);
        rules = setupLoopingRules(rules);
        TestUtils.putRoutingRules(rules);

        // configure request hops limit to 2
        with().body("{\"request.hops.limit\":0}").put(ROUTING_CONFIG).then().assertThat().statusCode(200);

        // prepare test data
        with().body("{\"someKey\":"+System.currentTimeMillis()+"}").put("/loop/4/resource").then().assertThat()
                .statusCode(500)
                .statusLine(containsString("Request hops limit exceeded"))
                .body(containsString(buildLimitExceededMessage(0)));

        when().get("/loop/4/resource").then().assertThat()
                .statusCode(500)
                .statusLine(containsString("Request hops limit exceeded"))
                .body(containsString(buildLimitExceededMessage(0)));

        delete(ROUTING_CONFIG);
        validateRoutingConfig(false, 0);

        with().body("{\"someKey\":"+System.currentTimeMillis()+"}").put("/loop/4/resource").then().assertThat().statusCode(200);
        when().get("/loop/4/resource").then().assertThat().statusCode(200);

        async.complete();
    }

    @Test
    public void testRequestHopValidationManualHeaderValue(TestContext context) {
        Async async = context.async();
        delete();

        // add a routing
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);
        rules = setupLoopingRules(rules);
        TestUtils.putRoutingRules(rules);

        // configure request hops limit to 10
        with().body("{\"request.hops.limit\":10}").put(ROUTING_CONFIG).then().assertThat().statusCode(200);
        validateRoutingConfig(true, 10);

        // prepare test data
        with().body("{\"someKey\":"+System.currentTimeMillis()+"}").put("/some/storage/resource").then().assertThat().statusCode(200);
        when().get("/some/storage/resource").then().assertThat().statusCode(200);

        given().header(X_HOPS.getName(), "9").when().get("/some/storage/resource").then().assertThat()
                .statusCode(200);
        given().header(X_HOPS.getName(), "10").when().get("/some/storage/resource").then().assertThat()
                .statusCode(500)
                .statusLine(containsString("Request hops limit exceeded"))
                .body(containsString(buildLimitExceededMessage(10)));
        given().header(X_HOPS.getName(), "50").when().get("/some/storage/resource").then().assertThat()
                .statusCode(500)
                .statusLine(containsString("Request hops limit exceeded"))
                .body(containsString(buildLimitExceededMessage(10)));

        delete(ROUTING_CONFIG);
        validateRoutingConfig(false, 0);

        given().header(X_HOPS.getName(), "10").when().get("/some/storage/resource").then().assertThat()
                .statusCode(200);
        given().header(X_HOPS.getName(), "50").when().get("/some/storage/resource").then().assertThat()
                .statusCode(200);
        given().header(X_HOPS.getName(), "999").when().get("/some/storage/resource").then().assertThat()
                .statusCode(200);

        async.complete();
    }

    private String buildLimitExceededMessage(int limit){
        return "Request hops limit of '"+limit+"' has been exceeded. Check the routing rules for looping configurations";
    }

    private void validateRoutingConfig(boolean configPresent, int configValue){
        if(configPresent){
            when().get(ROUTING_CONFIG).then().assertThat().statusCode(200).body("'request.hops.limit'", equalTo(configValue));
        } else {
            when().get(ROUTING_CONFIG).then().assertThat().statusCode(404);
        }
    }

    private JsonObject setupLoopingRules(JsonObject rules){
        rules = TestUtils.addRoutingRule(rules, AbstractTest.SERVER_ROOT + "/loop/1/(.*)",
                TestUtils.createRoutingRule(ImmutableMap.of(
                        "description",
                        "looping test rule 1",
                        "metricName",
                        "loop_1",
                        "path",
                        "/playground/server/loop/2/$1"))
        );

        rules = TestUtils.addRoutingRule(rules, AbstractTest.SERVER_ROOT + "/loop/2/(.*)",
                TestUtils.createRoutingRule(ImmutableMap.of(
                        "description",
                        "looping test rule 2",
                        "metricName",
                        "loop_2",
                        "path",
                        "/playground/server/loop/3/$1"))
        );

        rules = TestUtils.addRoutingRule(rules, AbstractTest.SERVER_ROOT + "/loop/3/(.*)",
                TestUtils.createRoutingRule(ImmutableMap.of(
                        "description",
                        "looping test rule 3",
                        "metricName",
                        "loop_3",
                        "path",
                        "/playground/server/loop/4/$1"))
        );

        rules = TestUtils.addRoutingRule(rules, AbstractTest.SERVER_ROOT + "/loop/4/(.*)",
                TestUtils.createRoutingRule(ImmutableMap.of(
                        "description",
                        "looping test rule 4",
                        "metricName",
                        "loop_4",
                        "path",
                        "/playground/server/loop/4/$1",
                        "storage",
                        "main"))
        );

        rules = TestUtils.addRoutingRule(rules, AbstractTest.SERVER_ROOT + "/looping/(.*)",
                TestUtils.createRoutingRule(ImmutableMap.of(
                        "description",
                        "endless looping rule",
                        "metricName",
                        "endless_looping",
                        "path",
                        "/playground/server/looping/$1"))
        );

        return rules;
    }
}

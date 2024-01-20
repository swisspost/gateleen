package org.swisspush.gateleen.expansion;

import com.google.common.collect.ImmutableMap;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;

import static io.restassured.RestAssured.*;
import static org.hamcrest.CoreMatchers.equalTo;

/**
 * Tests the check a special case where the expand handler is not really expanding the
 * request, but directly forwarding the request to the backend.
 * 
 * @author https://github.com/hofer [Marc Hofer]
 */
@RunWith(VertxUnitRunner.class)
public class ExpandByBackendTest extends AbstractTest {

    Logger log = LoggerFactory.getLogger(ExpandByBackendTest.class);

    @Test
    public void testDefaultExpand(TestContext context) {
        Async async = context.async();
        delete();
        createLocalRoutingRule();

        with().body("{ \"foo\": \"bar1\" }").put("resources/res1");
        with().body("{ \"foo\": \"bar2\" }").put("resources/res2");
        given().param("expand", 1).when().get("resources/").then().assertThat()
                .body("resources.res1.foo", equalTo("bar1"))
                .body("resources.res2.foo", equalTo("bar2"));

        async.complete();
    }

    private void createLocalRoutingRule() {
        JsonObject newRule =  TestUtils.createRoutingRule(ImmutableMap.of(
                "description",
                "ExpandTest which should not expand but route to backend.",
                "expandOnBackend",
                true,
                "url",
                "http://localhost:8989" + SERVER_ROOT + "/resources/res1"));

        // create routing rules
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);

        String TEST_RULE_NAME = "/test/backend(.*)";
        rules = TestUtils.addRoutingRule(rules, TEST_RULE_NAME, newRule);

        TestUtils.putRoutingRules(rules);
    }

    @Test
    public void testExpandIsForwardingToBackendWithoutExpand(TestContext context) {
        Async async = context.async();
        delete();
        createRemoteRoutingRule();
        createBackend();

        given().param("expand", 1)
               .when().get("backend/shouldexpand")
               .then().log().all().assertThat().body("expandonbackend", equalTo("success"));

        async.complete();
    }

    private void createRemoteRoutingRule() {
        JsonObject newRule =  TestUtils.createRoutingRule(ImmutableMap.of(
                "description",
                "ExpandTest which should to backend.",
                "expandOnBackend",
                true,
                "url",
                "http://localhost:9999" + SERVER_ROOT + "/resources/res1/$1"));

        // create routing rules
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);

        String TEST_RULE_NAME = "/playground/backend(.*)";
        rules = TestUtils.addRoutingRule(rules, TEST_RULE_NAME, newRule);

        TestUtils.putRoutingRules(rules);
    }

    private void createBackend() {

        HttpServer httpServer = vertx.createHttpServer().requestHandler(req -> {
            log.info("got request in backend: {}", req.path());
            req.response().headers().set("Content-Type", "application/json");
            if(req.uri().indexOf("expand") > 1) {
                req.response().end("{\"expandonbackend\": \"success\"}");
            } else {
                req.response().end("{\"expandonbackend\": \"failed\"}");
            }
        });

        httpServer.listen(9999, event -> log.info("created http server on port 9999, {}", event.result()));
    }
}

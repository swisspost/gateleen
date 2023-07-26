package org.swisspush.gateleen.routing;

import com.google.common.collect.ImmutableMap;
import io.restassured.RestAssured;
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

import static io.restassured.RestAssured.*;
import static org.hamcrest.CoreMatchers.equalTo;

/**
 * Test class for the headersFilter routing feature.
 * 
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class HeadersFilterForwardTest extends AbstractTest {

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath(SERVER_ROOT);
    }

    @Test
    public void testStorageForwarding(TestContext context) {
        Async async = context.async();
        delete();

        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);

        JsonObject rule = TestUtils.createRoutingRule(ImmutableMap.of(
                "path",
                "/playground/server/tests/path_y/res1",
                "methods",
                new JsonArray(List.of("PUT")),
                "storage",
                "main"));
        rules = TestUtils.addRoutingRule(rules, "/playground/server/tests/res1", rule);

        rule = TestUtils.createRoutingRule(ImmutableMap.of(
                "headersFilter",
                "x-foo.*",
                "path",
                "/playground/server/tests/path_x/$1",
                "methods",
                new JsonArray(List.of("PUT")),
                "storage",
                "main"));
        rules = TestUtils.addRoutingRule(rules, "/playground/server/tests/(.*)", rule);

        TestUtils.putRoutingRules(rules);

        get("/tests/path_x/res1").then().assertThat().statusCode(404);
        get("/tests/path_y/res1").then().assertThat().statusCode(404);

        // PUT with header
        with().body("{ \"foo\": \"bar1\" }").header("x-foo", "bar").put("/tests/res1").then().assertThat().statusCode(200);

        // new resource /tests/path_x/res1 should have been created
        get("/tests/path_x/res1").then().assertThat().statusCode(200).body("foo", equalTo("bar1"));

        // resource /tests/path_y/res1 should not exist yet
        get("/tests/path_y/res1").then().assertThat().statusCode(404);

        // PUT without header
        with().body("{ \"foo\": \"bar2\" }").put("/tests/res1").then().assertThat().statusCode(200);

        // resource /tests/path_x/res1 should not have changed
        get("/tests/path_x/res1").then().assertThat().statusCode(200).body("foo", equalTo("bar1"));

        // new resource /tests/path_y/res1 should have been created
        get("/tests/path_y/res1").then().assertThat().statusCode(200).body("foo", equalTo("bar2"));

        // PUT without matching header
        with().body("{ \"foo\": \"bar3\" }").header("x-bar", "foo").put("/tests/res1").then().assertThat().statusCode(200);

        // resource /tests/path_x/res1 should not have changed
        get("/tests/path_x/res1").then().assertThat().statusCode(200).body("foo", equalTo("bar1"));

        // new resource /tests/path_y/res1 should have been updated
        get("/tests/path_y/res1").then().assertThat().statusCode(200).body("foo", equalTo("bar3"));

        async.complete();
    }
}

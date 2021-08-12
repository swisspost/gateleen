package org.swisspush.gateleen.routing;

import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;
import io.restassured.RestAssured;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import static io.restassured.RestAssured.*;

/**
 * Test class for null routing feature.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
@RunWith(VertxUnitRunner.class)
public class NullForwardTest extends AbstractTest {

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath(SERVER_ROOT);
    }

    @Test
    public void testPutGetDelete(TestContext context) {
        Async async = context.async();
        delete();

        // add a routing
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);
        rules = TestUtils.addRoutingRuleHooks(rules);
        TestUtils.putRoutingRules(rules);

        // Request with payload - T1
        with().body("{ \"foo\": \"bar\" }").put("null").then().assertThat().statusCode(200);

        // Request with payload - T2 (to proof that the request payload "forgetting" works)
        with().body("{ \"foo\": \"bar2\" }").put("null").then().assertThat().statusCode(200);

        when().get("null").then().assertThat().header("Content-Length", "0");
        delete("null").then().assertThat().statusCode(200);

        async.complete();
    }
}

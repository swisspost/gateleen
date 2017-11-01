package org.swisspush.gateleen.core.cors;

import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;
import com.google.common.collect.ImmutableMap;
import com.jayway.restassured.RestAssured;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import static com.jayway.restassured.RestAssured.*;
import static org.hamcrest.CoreMatchers.*;

/**
 * Tests the CORS functionality of gateleen
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class CORSTest extends AbstractTest {

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath(SERVER_ROOT + "/");
    }

    @Test
    public void testGetForwarderWithoutOrigin(TestContext context) throws InterruptedException {
        init(createForwarderRoutingRule());
        when().get("tests/gateleen/cors/TestResource").then().assertThat()
                .statusCode(200)
                .body("foo", equalTo("bar"))
                .header("Access-Control-Allow-Origin", is(nullValue()))
                .header("Access-Control-Allow-Credentials", is(nullValue()))
                .header("Access-Control-Allow-Methods", is(nullValue()));
    }

    @Test
    public void testGetForwarderWithOrigin(TestContext context) throws InterruptedException {
        init(createForwarderRoutingRule());
        given().header("Origin", "http://127.0.0.1:8888").when().get("tests/gateleen/cors/TestResource").then().assertThat()
                .statusCode(200)
                .body("foo", equalTo("bar"))
                .header("Access-Control-Allow-Origin", is("http://127.0.0.1:8888"))
                .header("Access-Control-Allow-Credentials", is("true"))
                .header("Access-Control-Allow-Methods", is("GET, POST, OPTIONS, PUT, DELETE"));
    }

    @Test
    public void testGetStorageForwarderWithoutOrigin(TestContext context) throws InterruptedException {
        init(createStorageForwarderRoutingRule());
        when().get("tests/gateleen/cors/TestResource").then().assertThat()
                .statusCode(200)
                .body("foo", equalTo("bar"))
                .header("Access-Control-Allow-Origin", is(nullValue()))
                .header("Access-Control-Allow-Credentials", is(nullValue()))
                .header("Access-Control-Allow-Methods", is(nullValue()));
    }

    @Test
    public void testGetStorageForwarderWithOrigin(TestContext context) throws InterruptedException {
        init(createStorageForwarderRoutingRule());
        given().header("Origin", "http://127.0.0.1:8888").when().get("tests/gateleen/cors/TestResource").then().assertThat()
                .statusCode(200)
                .body("foo", equalTo("bar"))
                .header("Access-Control-Allow-Origin", is("http://127.0.0.1:8888"))
                .header("Access-Control-Allow-Credentials", is("true"))
                .header("Access-Control-Allow-Methods", is("GET, POST, OPTIONS, PUT, DELETE"));
    }

    /**
     * Init the test.
     * 
     * @param newRule the rule we want to check
     * @throws InterruptedException
     */
    private void init(JsonObject newRule) throws InterruptedException {
        delete();

        with().body("{ \"foo\": \"bar\" }").put("tests/gateleen/cors/TestResource");

        // create routing rules
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);

        String TEST_RULE_NAME = SERVER_ROOT + "/tests/gateleen/cors/(.*)";
        rules = TestUtils.addRoutingRule(rules, TEST_RULE_NAME, newRule);

        TestUtils.putRoutingRules(rules);

        // test if new rule is in rules resource
        get("admin/v1/routing/rules").then().assertThat().body(containsString(TEST_RULE_NAME)).statusCode(200);
    }

    private JsonObject createForwarderRoutingRule() {
        return TestUtils.createRoutingRule(ImmutableMap.of(
                "description",
                "RoutingRule to test CORS with the Forwarder (http).",
                "url",
                "http://localhost:8989" + SERVER_ROOT + "/tests/gateleen/cors/$1"));
    }

    private JsonObject createStorageForwarderRoutingRule() {
        return TestUtils.createRoutingRule(ImmutableMap.of(
                "description",
                "RoutingRule to test CORS with the StorageForwarder.",
                "path",
                SERVER_ROOT + "/tests/gateleen/cors/$1",
                "storage",
                "main"));
    }
}

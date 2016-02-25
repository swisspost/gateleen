package org.swisspush.gateleen.expansion;

import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;
import com.google.common.collect.ImmutableMap;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Assert;
import org.junit.Test;
import io.vertx.core.json.JsonObject;
import org.junit.runner.RunWith;

import static com.jayway.restassured.RestAssured.*;
import static org.hamcrest.CoreMatchers.*;

/**
 * Tests the check a special case where the expand handler is not really expanding the
 * request, but directly forwarding the request to the storage (where the expansion happens).
 * 
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class StorageExpandTest extends AbstractTest {

    @Test
    public void testDefaultExpandWithoutStorageExpand(TestContext context) {
        Async async = context.async();
        delete();
        createRoutingRule(false);

        with().body("{ \"foo\": \"bar1\" }").put("/exp/res1");
        with().body("{ \"foo\": \"bar2\" }").put("/exp/res2");

        given().param("expand", 1).when().get("/exp/").then()
                .assertThat()
                .body("keySet().size()", is(1)) // only one root node
                .body("exp.keySet().size()", is(2))
                .body("exp.res1.foo", equalTo("bar1"))
                .body("exp.res2.foo", equalTo("bar2"));

        async.complete();
    }

    @Test
    public void testSimpleResources(TestContext context) {
        Async async = context.async();
        delete();
        createRoutingRule(true);

        with().body("{ \"foo\": \"bar1\" }").put("/exp/res1");
        with().body("{ \"foo\": \"bar2\" }").put("/exp/res2");

        given().param("expand", 1).when().get("/exp/").then()
                .assertThat()
                .body("keySet().size()", is(1)) // only one root node
                .body("exp.keySet().size()", is(2))
                .body("exp.res1.foo", equalTo("bar1"))
                .body("exp.res2.foo", equalTo("bar2"));

        async.complete();
    }

    @Test
    public void testResultEquality(TestContext context){
        Async async = context.async();
        delete();

        with().body("{ \"foo\": \"bar1\" }").put("/items/exp/res1");
        with().body("{ \"foo\": \"bar2\" }").put("/items/exp/res2");
        with().body("{ \"foo\": \"bar3\" }").put("/items/exp/sub/res3");
        with().body("{ \"foo\": \"bar4\" }").put("/items/exp/sub/res4");

        String uri = "/items/exp/";
        String standardExpand = extractResponseBody(uri, false);
        String storageExpand = extractResponseBody(uri, true);
        Assert.assertEquals(standardExpand, storageExpand);

        uri = "/items/";
        standardExpand = extractResponseBody(uri, false);
        storageExpand = extractResponseBody(uri, true);
        Assert.assertEquals(standardExpand, storageExpand);

        async.complete();
    }

    @Test
    public void testResourcesAndCollections(TestContext context) {
        Async async = context.async();
        delete();
        createRoutingRule(true);

        with().body("{ \"foo\": \"bar1\" }").put("/items/exp/res1");
        with().body("{ \"foo\": \"bar2\" }").put("/items/exp/res2");
        with().body("{ \"foo\": \"bar3\" }").put("/items/exp/sub/res3");
        with().body("{ \"foo\": \"bar4\" }").put("/items/exp/sub/res4");

        given().param("expand", 1).when().get("/items/exp/").then()
                .assertThat()
                .body("keySet().size()", is(1)) // only one root node
                .body("exp.keySet().size()", is(3))
                .body("exp.sub", hasItems("res3", "res4"))
                .body("exp.res1.foo", equalTo("bar1"))
                .body("exp.res2.foo", equalTo("bar2"));

        given().param("expand", 1).when().get("/items/").then().log().body()
                .assertThat()
                .body("items.exp", hasItems("res1", "res2", "sub/"));

        async.complete();
    }

    @Test
    public void testCollectionsOnly(TestContext context) {
        Async async = context.async();
        delete();
        createRoutingRule(true);

        with().body("{ \"foo\": \"bar3\" }").put("/items/exp/sub/res1");
        with().body("{ \"foo\": \"bar3\" }").put("/items/exp/sub/res2");
        with().body("{ \"foo\": \"bar3\" }").put("/items/exp/anothersub/res3");
        with().body("{ \"foo\": \"bar4\" }").put("/items/exp/anothersub/res4");

        given().param("expand", 1).when().get("/items/exp/").then()
                .assertThat()
                .body("keySet().size()", is(1)) // only one root node
                .body("exp.keySet().size()", is(2))
                .body("exp.sub", hasItems("res1", "res2"))
                .body("exp.anothersub", hasItems("res3", "res4"));

        given().param("expand", 1).when().get("/items/").then().log().body()
                .assertThat()
                .body("items.exp", hasItems("anothersub/", "sub/"));

        async.complete();
    }

    @Test
    public void testWithStorageExpandAndExpandValueTooHigh(TestContext context) {
        Async async = context.async();
        delete();
        createRoutingRule(true);

        with().body("{ \"foo\": \"bar1\" }").put("/test/exp/res1");
        with().body("{ \"foo\": \"bar2\" }").put("/test/exp/res2");

        given().param("expand", 3).when().get("/test/exp/").then().log().body()
                .assertThat().statusCode(400)
                .body(containsString("Expand values higher than 1 are not supported for storageExpand requests"));

        async.complete();
    }

    private String extractResponseBody(String uri, boolean storageExpand){
        createRoutingRule(storageExpand);
        return given().param("expand", 1).when().get(uri).getBody().asString();
    }

    private void createRoutingRule(boolean storageExpand) {
        JsonObject newRule =  TestUtils.createRoutingRule(ImmutableMap.of(
                "description",
                "ExpandTest which should expand in storage.",
                "storageExpand",
                storageExpand,
                "storage",
                "main",
                "path",
                "/test/$1"));

        // create routing rules
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);

        String TEST_RULE_NAME = "/test/(.*)";
        rules = TestUtils.addRoutingRule(rules, TEST_RULE_NAME, newRule);

        TestUtils.putRoutingRules(rules);
    }
}

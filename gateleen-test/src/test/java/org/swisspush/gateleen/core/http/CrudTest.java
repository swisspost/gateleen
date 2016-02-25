package org.swisspush.gateleen.core.http;

import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;
import com.jayway.restassured.RestAssured;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import static com.jayway.restassured.RestAssured.*;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItems;

@RunWith(VertxUnitRunner.class)
public class CrudTest extends AbstractTest {

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath("/server/tests/crud");
        RestAssured.requestSpecification.baseUri("http://localhost:" + MAIN_PORT + ROOT);
    }

    /**
     * Init the test
     */
    private void init() {
        RestAssured.requestSpecification.basePath("");
        delete();

        // add a routing
        JsonObject rules = TestUtils.addRoutingRuleMainStorage(new JsonObject());
        TestUtils.putRoutingRules(rules);

        // add an empty resource
        with().body("{}").put("/server/tests/empty");

        // set base
        RestAssured.requestSpecification.basePath("/server/tests/crud");
    }

    @Test
    public void testPutGetDelete(TestContext context) {
        Async async = context.async();
        init();

        with().body("{ \"foo\": \"bar\" }").put("res");

        TestUtils.checkGETStatusCodeWithAwait("res", 200);
        when().get("res").then().assertThat().body("foo", equalTo("bar"));

        delete("res");

        TestUtils.checkGETStatusCodeWithAwait("res", 404);

        async.complete();
    }

    @Test
    public void testList(TestContext context) {
        Async async = context.async();
        init();

        with().body("{ \"foo\": \"bar\" }").put("resources/res1");
        with().body("{ \"foo\": \"bar2\" }").put("resources/res2");

        TestUtils.checkGETStatusCodeWithAwait("resources/res1", 200);
        TestUtils.checkGETStatusCodeWithAwait("resources/res2", 200);

        when().get("resources").then().assertThat().body("resources", hasItems("res1", "res2"));

        async.complete();
    }

    @Test
    public void testMerge(TestContext context) {
        Async async = context.async();
        init();

        with().body("{ \"foo\": \"bar\", \"hello\": \"world\" }").put("res");
        TestUtils.checkGETStatusCodeWithAwait("res", 200);

        with().param("merge", "true").body("{ \"foo\": \"bar2\" }").put("res");
        TestUtils.checkGETStatusCodeWithAwait("res", 200);

        get("res").then().assertThat().statusCode(200).body("foo", equalTo("bar2")).body("hello", equalTo("world"));

        async.complete();
    }

    @Test
    public void testTwoBranchesDeleteOnLeafOfOneBranch(TestContext context) {
        Async async = context.async();
        init();

        with().body("{ \"foo\": \"bar1\" }").put("branch1/res/leaf");
        with().body("{ \"foo\": \"bar2\" }").put("branch2/res/leaf");
        TestUtils.checkGETStatusCodeWithAwait("branch1/res/leaf", 200);
        TestUtils.checkGETStatusCodeWithAwait("branch2/res/leaf", 200);

        delete("branch2/res/leaf");
        TestUtils.checkGETStatusCodeWithAwait("branch2/res/leaf", 404);

        RestAssured.requestSpecification.basePath("");

        TestUtils.checkGETStatusCodeWithAwait("/server", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch1", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch1/res", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch1/res/leaf", 200);
        when().get("/server/tests/crud/branch1/res/leaf").then().assertThat().statusCode(200).body("foo", equalTo("bar1"));
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch2", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch2/res", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch2/res/leaf", 404);

        async.complete();
    }

    @Test
    public void testTwoBranchesDeleteOnNodeUnderLeafOfOneBranch(TestContext context) {
        Async async = context.async();
        init();

        with().body("{ \"foo\": \"bar1\" }").put("branch1/res/res1/leaf");
        with().body("{ \"foo\": \"bar2\" }").put("branch2/res/res2/leaf");
        TestUtils.checkGETStatusCodeWithAwait("branch1/res/res1/leaf", 200);
        TestUtils.checkGETStatusCodeWithAwait("branch2/res/res2/leaf", 200);

        delete("branch2/res");
        TestUtils.checkGETStatusCodeWithAwait("branch2/res", 404);

        RestAssured.requestSpecification.basePath("");

        TestUtils.checkGETStatusCodeWithAwait("/server", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch1", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch1/res", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch1/res/res1/", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch1/res/res1/leaf", 200);
        when().get("/server/tests/crud/branch1/res/res1/leaf").then().assertThat().statusCode(200).body("foo", equalTo("bar1"));
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch2", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch2/res", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch2/res/res2", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch2/res/res2/leaf", 404);

        async.complete();
    }

    @Test
    public void testTwoBranchesDeleteOnNodeAfterBranch(TestContext context) {
        Async async = context.async();
        init();

        with().body("{ \"foo\": \"bar1\" }").put("branch1/res/res1/leaf");
        with().body("{ \"foo\": \"bar2\" }").put("branch2/res/res2/leaf");
        TestUtils.checkGETStatusCodeWithAwait("branch1/res/res1/leaf", 200);
        TestUtils.checkGETStatusCodeWithAwait("branch2/res/res2/leaf", 200);

        delete("branch1/res");
        TestUtils.checkGETStatusCodeWithAwait("branch1/res", 404);

        RestAssured.requestSpecification.basePath("");

        TestUtils.checkGETStatusCodeWithAwait("/server", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch2", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch2/res", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch2/res/res2/leaf", 200);
        when().get("/server/tests/crud/branch2/res/res2/leaf").then().assertThat().statusCode(200).body("foo", equalTo("bar2"));
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch1", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch1/res", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch1/res/leaf", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch1/res/res1/leaf", 404);

        async.complete();
    }

    @Test
    public void testTwoBranchesDeleteOnRoot(TestContext context) {
        Async async = context.async();
        init();

        with().body("{ \"foo\": \"bar\" }").put("node/node/node/branch1/res/res1/leaf");
        with().body("{ \"foo\": \"bar\" }").put("node/node/node/branch2/res/res2/leaf");
        TestUtils.checkGETStatusCodeWithAwait("node/node/node/branch1/res/res1/leaf", 200);
        TestUtils.checkGETStatusCodeWithAwait("node/node/node/branch2/res/res2/leaf", 200);

        RestAssured.requestSpecification.basePath("");

        delete("/server/tests/crud");
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud", 404);

        TestUtils.checkGETStatusCodeWithAwait("/server", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/node", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/node/node", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/node/node/branch2", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/node/node/branch2/res", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/node/node/branch2/res/res2/leaf", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/node/node/branch1", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/node/node/branch1/res", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/node/node/branch1/res/leaf", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/node/node/branch1/res/res1/leaf", 404);

        async.complete();
    }

    @Test
    public void testThreeBranchesDeleteOnLeafOfOneBranch(TestContext context) {
        Async async = context.async();
        init();

        with().body("{ \"foo\": \"bar1\" }").put("node/branch1/res/leaf");
        with().body("{ \"foo\": \"bar2\" }").put("node/branch2/res/leaf");
        with().body("{ \"foo\": \"bar3\" }").put("node/branch3/res/leaf");
        TestUtils.checkGETStatusCodeWithAwait("node/branch1/res/leaf", 200);
        TestUtils.checkGETStatusCodeWithAwait("node/branch2/res/leaf", 200);
        TestUtils.checkGETStatusCodeWithAwait("node/branch3/res/leaf", 200);

        delete("node/branch3/res/leaf");
        TestUtils.checkGETStatusCodeWithAwait("node/branch3/res/leaf", 404);

        RestAssured.requestSpecification.basePath("");

        TestUtils.checkGETStatusCodeWithAwait("/server", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/branch1", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/branch1/res", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/branch1/res/leaf", 200);
        when().get("/server/tests/crud/node/branch1/res/leaf").then().assertThat().statusCode(200).body("foo", equalTo("bar1"));
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/branch2", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/branch2/res", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/branch2/res/leaf", 200);
        when().get("/server/tests/crud/node/branch2/res/leaf").then().assertThat().statusCode(200).body("foo", equalTo("bar2"));
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/branch3", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/branch3/res", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/branch3/res/leaf", 404);

        async.complete();
    }

    @Test
    public void testThreeBranchesDeleteOnNodeUnderLeafOfOneBranch(TestContext context) {
        Async async = context.async();
        init();

        with().body("{ \"foo\": \"bar1\" }").put("branch1/res/res1/leaf");
        with().body("{ \"foo\": \"bar2\" }").put("branch2/res/res2/leaf");
        with().body("{ \"foo\": \"bar3\" }").put("branch3/res/res3/leaf");
        TestUtils.checkGETStatusCodeWithAwait("branch1/res/res1/leaf", 200);
        TestUtils.checkGETStatusCodeWithAwait("branch2/res/res2/leaf", 200);
        TestUtils.checkGETStatusCodeWithAwait("branch3/res/res3/leaf", 200);

        delete("branch1/res");
        TestUtils.checkGETStatusCodeWithAwait("branch1/res", 404);

        RestAssured.requestSpecification.basePath("");

        TestUtils.checkGETStatusCodeWithAwait("/server", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch1", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch1/res", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch1/res/res1/", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch1/res/res1/leaf", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch2", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch2/res", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch2/res/res2", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch2/res/res2/leaf", 200);
        when().get("/server/tests/crud/branch2/res/res2/leaf").then().assertThat().statusCode(200).body("foo", equalTo("bar2"));
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch3", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch3/res", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch3/res/res3", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch3/res/res3/leaf", 200);
        when().get("/server/tests/crud/branch3/res/res3/leaf").then().assertThat().statusCode(200).body("foo", equalTo("bar3"));

        async.complete();
    }

    @Test
    public void testThreeBranchesDeleteOnNodeAfterBranch(TestContext context) {
        Async async = context.async();
        init();

        with().body("{ \"foo\": \"bar1\" }").put("branch1/res/res1/leaf");
        with().body("{ \"foo\": \"bar2\" }").put("branch2/res/res2/leaf");
        with().body("{ \"foo\": \"bar3\" }").put("branch3/res/res3/leaf");
        TestUtils.checkGETStatusCodeWithAwait("branch1/res/res1/leaf", 200);
        TestUtils.checkGETStatusCodeWithAwait("branch2/res/res2/leaf", 200);
        TestUtils.checkGETStatusCodeWithAwait("branch3/res/res3/leaf", 200);

        delete("branch2/res");
        TestUtils.checkGETStatusCodeWithAwait("branch2/res", 404);

        RestAssured.requestSpecification.basePath("");

        TestUtils.checkGETStatusCodeWithAwait("/server", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch1", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch1/res", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch1/res/res1", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch1/res/res1/leaf", 200);
        when().get("/server/tests/crud/branch1/res/res1/leaf").then().assertThat().statusCode(200).body("foo", equalTo("bar1"));
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch2", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch2/res", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch2/res/res2", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch2/res/res2/leaf", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch3", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch3/res", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch3/res/res3", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/branch3/res/res3/leaf", 200);
        when().get("/server/tests/crud/branch3/res/res3/leaf").then().assertThat().statusCode(200).body("foo", equalTo("bar3"));

        async.complete();
    }

    @Test
    public void testThreeBranchesDeleteOnRoot(TestContext context) {
        Async async = context.async();
        init();

        with().body("{ \"foo\": \"bar\" }").put("node/node/node/branch1/res/res1/leaf");
        with().body("{ \"foo\": \"bar\" }").put("node/node/node/branch2/res/res2/leaf");
        with().body("{ \"foo\": \"bar\" }").put("node/node/node/branch3/res/res3/leaf");
        TestUtils.checkGETStatusCodeWithAwait("node/node/node/branch1/res/res1/leaf", 200);
        TestUtils.checkGETStatusCodeWithAwait("node/node/node/branch2/res/res2/leaf", 200);
        TestUtils.checkGETStatusCodeWithAwait("node/node/node/branch3/res/res3/leaf", 200);

        RestAssured.requestSpecification.basePath("");

        delete("/server/tests/crud");
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud", 404);

        TestUtils.checkGETStatusCodeWithAwait("/server", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests", 200);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/node", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/node/node", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/node/node/branch2", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/node/node/branch2/res", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/node/node/branch2/res/res2/leaf", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/node/node/branch1", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/node/node/branch1/res", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/node/node/branch1/res/leaf", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/node/node/branch1/res/res1/leaf", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/node/node/branch3", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/node/node/branch3/res", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/node/node/branch3/res/leaf", 404);
        TestUtils.checkGETStatusCodeWithAwait("/server/tests/crud/node/node/node/branch3/res/res3/leaf", 404);

        async.complete();
    }
}

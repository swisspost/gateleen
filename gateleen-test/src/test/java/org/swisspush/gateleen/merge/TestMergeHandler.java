package org.swisspush.gateleen.merge;

import com.google.common.collect.ImmutableMap;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;
import org.swisspush.gateleen.core.util.StatusCode;

import static io.restassured.RestAssured.delete;
import static io.restassured.RestAssured.given;

/**
 * Tests some features of the MergeHandler.
 *
 * @author https://github.com/ljucam [Mario Aerni]
 */
@RunWith(VertxUnitRunner.class)
public class TestMergeHandler extends AbstractTest {
    private String targetUrlBase;
    private JsonObject expectedExpandResult;

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath(SERVER_ROOT);
        RestAssured.requestSpecification.urlEncodingEnabled(false);
    }


    /**
     * Init the routing roules for the hooking.
     */
    private void initRoutingRules() {
        // add a routing
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);
        rules = TestUtils.addRoutingRuleHooks(rules);

        String requestUrlBase = AbstractTest.SERVER_ROOT + "/data/";
        targetUrlBase = "/masterdata/parent/";
        String targetUrl = AbstractTest.SERVER_ROOT + targetUrlBase;

        JsonObject headers = new JsonObject();
        headers.put("x-merge-collections", targetUrl);

        JsonObject mergeRule = TestUtils.createRoutingRule(ImmutableMap.of(
            "path", "/data/$1",
            "staticHeaders", headers
        ));

        rules = TestUtils.addRoutingRule(rules, requestUrlBase + "(.*)", mergeRule);

        // rules for dynamic routing
        // -----
        targetUrl =  AbstractTest.SERVER_ROOT + "/tests/gateleen/routesource/";
        headers = new JsonObject();
        headers.put("x-merge-collections", targetUrl);

        mergeRule = TestUtils.createRoutingRule(ImmutableMap.of(
                "path", "/dynamicdata/$1",
                "staticHeaders", headers
        ));

        rules = TestUtils.addRoutingRule(rules, AbstractTest.SERVER_ROOT + "/dynamicdata/" + "(.*)", mergeRule);
        // -----



        TestUtils.putRoutingRules(rules);
    }

    private void createTestData() {
        expectedExpandResult = new JsonObject();
        JsonObject tierX = new JsonObject();
        JsonObject tierX2 = new JsonObject();
        JsonObject tierX3 = new JsonObject();

        expectedExpandResult.put("tierX", tierX);
        tierX.put("tierX2", tierX2);
        tierX2.put("tierX3", tierX3);

        // collection 1
        // ------------
        given().body(createTestObject("res1").toString()).put(targetUrlBase + "collection1/data/tier1/res1");
        given().body(createTestObject("res2").toString()).put(targetUrlBase + "collection1/data/tier1/res2").then().assertThat().statusCode(200);
        given().body(createTestObject("res3").toString()).put(targetUrlBase + "collection1/data/tier1/res3").then().assertThat().statusCode(200);
        given().body(createTestObject("res4").toString()).put(targetUrlBase + "collection1/data/tier1/tier2/res4").then().assertThat().statusCode(200);


        addJsonObject(tierX, "x1");
        given().body(createTestObject("resX1").toString()).put(targetUrlBase + "collection1/data/tierX/x1").then().assertThat().statusCode(200);
        addJsonObject(tierX2, "x2");
        given().body(createTestObject("resX2").toString()).put(targetUrlBase + "collection1/data/tierX/tierX2/x2").then().assertThat().statusCode(200);
        addJsonObject(tierX2, "x3");
        given().body(createTestObject("resX3").toString()).put(targetUrlBase + "collection1/data/tierX/tierX2/x3").then().assertThat().statusCode(200);
        addJsonObject(tierX3, "x4");
        given().body(createTestObject("resX4").toString()).put(targetUrlBase + "collection1/data/tierX/tierX2/tierX3/x4").then().assertThat().statusCode(200);


        // collection 2
        // ------------
        given().body(createTestObject("res5").toString()).put(targetUrlBase + "collection2/data/tier1/tier2/res5").then().assertThat().statusCode(200);
        given().body(createTestObject("res6").toString()).put(targetUrlBase + "collection2/data/tier1/res6").then().assertThat().statusCode(200);
        given().body(createTestObject("res7").toString()).put(targetUrlBase + "collection2/data/tier1/tier3/res7").then().assertThat().statusCode(200);
        given().body(createTestObject("res8").toString()).put(targetUrlBase + "collection2/data/tier1/tier4/res8").then().assertThat().statusCode(200);

        addJsonObject(tierX, "x5");
        given().body(createTestObject("resX5").toString()).put(targetUrlBase + "collection1/data/tierX/x5").then().assertThat().statusCode(200);
        addJsonObject(tierX2, "x6");
        given().body(createTestObject("resX6").toString()).put(targetUrlBase + "collection1/data/tierX/tierX2/x6").then().assertThat().statusCode(200);
        addJsonObject(tierX2, "x7");
        given().body(createTestObject("resX7").toString()).put(targetUrlBase + "collection1/data/tierX/tierX2/x7").then().assertThat().statusCode(200);
        addJsonObject(tierX3, "x8");
        given().body(createTestObject("resX8").toString()).put(targetUrlBase + "collection1/data/tierX/tierX2/tierX3/x8").then().assertThat().statusCode(200);


        // collection 3
        // ------------
        // only one will be taken
        given().body(createTestObject("res8").toString()).put(targetUrlBase + "collection3/data/tier1/tier4/res8").then().assertThat().statusCode(200);

        // error - missmatch
        given().body(createTestObject("res9").toString()).put(targetUrlBase + "collection3/data/tier1/tier3").then().assertThat().statusCode(200);

        addJsonObject(tierX, "x9");
        given().body(createTestObject("resX9").toString()).put(targetUrlBase + "collection1/data/tierX/x9").then().assertThat().statusCode(200);
        addJsonObject(tierX2, "x10");
        given().body(createTestObject("resX10").toString()).put(targetUrlBase + "collection1/data/tierX/tierX2/x10").then().assertThat().statusCode(200);
        addJsonObject(tierX2, "x11");
        given().body(createTestObject("resX11").toString()).put(targetUrlBase + "collection1/data/tierX/tierX2/x11").then().assertThat().statusCode(200);
        addJsonObject(tierX3, "x12");
        given().body(createTestObject("resX12").toString()).put(targetUrlBase + "collection1/data/tierX/tierX2/tierX3/x12").then().assertThat().statusCode(200);
    }

    private void createDynamicTestData() {
        // setting up routes
        String fromBase = "/tests/gateleen/routesource/";
        String toBase = "http://localhost:" + MAIN_PORT + SERVER_ROOT + "/tests/gateleen/parent/routetarget";

        String from = fromBase + "t1";
        String to = toBase  + "1";
        addRoute(from, to, true, true);

        from = fromBase + "t2";
        to = toBase  + "2";
        addRoute(from, to, true, true);

        from = fromBase + "t3";
        to = toBase  + "3";
        addRoute(from, to, true, true);
        // ----

        // provide some test data for routes
        expectedExpandResult = new JsonObject();
        JsonObject tier1 = new JsonObject();
        JsonObject tier2 = new JsonObject();
        JsonObject tier3 = new JsonObject();

        expectedExpandResult.put("tier1", tier1);
        tier1.put("tier2", tier2);
        tier2.put("tier3", tier3);

        addJsonObject(tier1, "t1");
        given().body(createTestObject("resT1").toString()).put(fromBase + "t1/dynamicdata/tier1/t1").then().assertThat().statusCode(200);
        addJsonObject(tier1, "t2");
        given().body(createTestObject("resT2").toString()).put(fromBase + "t1/dynamicdata/tier1/t2").then().assertThat().statusCode(200);
        addJsonObject(tier2, "t3");
        given().body(createTestObject("resT3").toString()).put(fromBase + "t1/dynamicdata/tier1/tier2/t3").then().assertThat().statusCode(200);
        addJsonObject(tier3, "t4");
        given().body(createTestObject("resT4").toString()).put(fromBase + "t1/dynamicdata/tier1/tier2/tier3/t4").then().assertThat().statusCode(200);

        addJsonObject(tier1, "t5");
        given().body(createTestObject("resT5").toString()).put(fromBase + "t2/dynamicdata/tier1/t5").then().assertThat().statusCode(200);
        addJsonObject(tier1, "t6");
        given().body(createTestObject("resT6").toString()).put(fromBase + "t2/dynamicdata/tier1/t6").then().assertThat().statusCode(200);
        addJsonObject(tier2, "t7");
        given().body(createTestObject("resT7").toString()).put(fromBase + "t2/dynamicdata/tier1/tier2/t7").then().assertThat().statusCode(200);
        addJsonObject(tier3, "t8");
        given().body(createTestObject("resT8").toString()).put(fromBase + "t2/dynamicdata/tier1/tier2/tier3/t8").then().assertThat().statusCode(200);

        addJsonObject(tier1, "t9");
        given().body(createTestObject("resT9").toString()).put(fromBase + "t3/dynamicdata/tier1/t9").then().assertThat().statusCode(200);
        addJsonObject(tier1, "t10");
        given().body(createTestObject("resT10").toString()).put(fromBase + "t3/dynamicdata/tier1/t10").then().assertThat().statusCode(200);
        addJsonObject(tier2, "t11");
        given().body(createTestObject("resT11").toString()).put(fromBase + "t3/dynamicdata/tier1/tier2/t11").then().assertThat().statusCode(200);
        addJsonObject(tier3, "t12");
        given().body(createTestObject("resT12").toString()).put(fromBase + "t3/dynamicdata/tier1/tier2/tier3/t12").then().assertThat().statusCode(200);
        // ----
    }

    private void addJsonObject(JsonObject parent, String name) {
        JsonObject child = new JsonObject();
        child.put("name", "res" + name.toUpperCase());
        parent.put(name, child);
    }

    @Test
    public void testMergeRequest_Direct(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();
        createTestData();

        // request /data/tier1/tier2/res5
        // unique representation
        Response response = given().get( "data/tier1/tier2/res5");
        Assert.assertEquals(StatusCode.OK.getStatusCode(), response.getStatusCode());
        Assert.assertNotNull(response.getBody());
        Assert.assertEquals(createTestObject("res5"), new JsonObject(response.getBody().asString()));


        // request /data/tierX/tier2/res5
        // not found
        response = given().get( "data/tierX/tier2/res5");
        Assert.assertEquals(StatusCode.NOT_FOUND.getStatusCode(), response.getStatusCode());

        // request /data/tier1/tier10/res5
        // not found
        response = given().get( "data/tier1/tier10/res5");
        Assert.assertEquals(StatusCode.NOT_FOUND.getStatusCode(), response.getStatusCode());

        // request /data/tier1/tier4/res8
        // multiple representation (only first one will be taken)
        response = given().get( "/data/tier1/tier4/res8");
        Assert.assertEquals(StatusCode.OK.getStatusCode(), response.getStatusCode());
        Assert.assertNotNull(response.getBody());
        Assert.assertEquals(createTestObject("res8"), new JsonObject(response.getBody().asString()));

        // request /data/tier1/tier4
        response = given().get( "data/tier1/tier4/");
        Assert.assertEquals(StatusCode.OK.getStatusCode(), response.getStatusCode());


        async.complete();
    }

    @Test
    public void testMergeRequest_Collection_Headers(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();
        createTestData();


        // request /data/tier1
        // should return an array
        Response r = given().get( "data/tier1");
        Assert.assertEquals(r.header("Content-Type"), "application/json");

        async.complete();
    }


    @Test
    public void testMergeRequest_Collection(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();
        createTestData();

        // request /data/ (special case, same as path)
        // should return an array with two elements
        assertCollectionResponse(given().get( "data/"),
                "data",
                new String[] {"tier1/", "tierX/"});

        // request /data/tier1
        // should return an array
        assertCollectionResponse(given().get( "data/tier1"),
                "tier1",
                new String[] {"tier2/","tier3/","tier4/","res1","res2","res3","res6","tier3"});

        // request /data/tier1/tier2
        // should return an array
        assertCollectionResponse(given().get( "data/tier1/tier2"),
                "tier2",
                new String[] {"res4", "res5"});

        // request /data/tier1/tier2/
        // should return an array
        assertCollectionResponse(given().get( "data/tier1/tier2/"),
                "tier2",
                new String[] {"res4", "res5"});


        // request /data/tier1/tier5/
        // shoud return 404 NOT FOUND
        given().get("/data/tier1/tier5/").then().assertThat().statusCode(StatusCode.NOT_FOUND.getStatusCode());

        // request /data/tier1/tier3
        // sould return a missmatch error
        given().get("/data/tier1/tier3").then().assertThat().statusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode())
                .and().assertThat().body(Matchers.equalTo(MergeHandler.MISSMATCH_ERROR));

        async.complete();
    }

    @Test
    public void testMergeRequest_Expand(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();
        createTestData();

        Response r = given().get("/data/tierX/?expand=100");
        Assert.assertEquals(StatusCode.OK.getStatusCode(), r.getStatusCode());
        Assert.assertNotNull(r.getBody());

        JsonObject result = new JsonObject(r.getBody().asString());
        Assert.assertEquals(expectedExpandResult, result);

        async.complete();
    }

    @Test
    public void testMergeRequest_DynamicRouting_Collection(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();
        createDynamicTestData();

        // collection request
        // ----
        // request /dynamicdata/ (special case)
        // should return an array with one element
        Response r = given().get( "dynamicdata/");
        assertCollectionResponse(r, "dynamicdata", new String[]{"tier1/"});

        // request /dynamicdata/tier1
        // should return an array
        assertCollectionResponse(given().get( "dynamicdata/tier1"),
                "tier1",
                new String[] {"tier2/","t1","t10","t2","t5","t6","t9"});


        // request /dynamicdata/tier1/tier5/
        // shoud return 404 NOT FOUND
        given().get("/dynamicdata/tier1/tier5/").then().assertThat().statusCode(StatusCode.NOT_FOUND.getStatusCode());

        // ----

        async.complete();
    }

    @Test
    public void testMergeRequest_DynamicRouting_Expand(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();
        createDynamicTestData();

        // expand request
        // ----
        Response r = given().get("/dynamicdata/tier1/?expand=100");
        Assert.assertEquals(StatusCode.OK.getStatusCode(), r.getStatusCode());
        Assert.assertNotNull(r.getBody());

        JsonObject result = new JsonObject(r.getBody().asString());
        Assert.assertEquals(expectedExpandResult, result);

        // ----

        async.complete();
    }

    @Test
    public void testMergeRequest_DynamicRouting_Direct(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();
        createDynamicTestData();

        // direct request
        // -----

        // request dynamicdata/tier1/t9
        // should be found
        Response r = given().get("dynamicdata/tier1/t9");
        Assert.assertEquals(StatusCode.OK.getStatusCode(), r.getStatusCode());
        Assert.assertNotNull(r.getBody());
        Assert.assertEquals(createTestObject("resT9"), new JsonObject(r.getBody().asString()));


        // request dynamicdata/tier1/t9
        // should not be found
        given().get("dynamicdata/tier1/t11").then().assertThat().statusCode(StatusCode.NOT_FOUND.getStatusCode());
        // -----

        async.complete();
    }

    private void assertCollectionResponse(final Response response, final String collection, final String[] expectedArray) {
        Assert.assertEquals(StatusCode.OK.getStatusCode(), response.statusCode());
        Assert.assertNotNull(response.getBody());
        String bodyString = response.getBody().asString();
        JsonObject body = new JsonObject(bodyString);
        JsonArray array = body.getJsonArray(collection);
        Assert.assertEquals(expectedArray.length, array.size());
        Assert.assertThat(array, Matchers.contains(expectedArray));
    }

    private JsonObject createTestObject(String name) {
        JsonObject resource = new JsonObject();
        resource.put("name", name);
        return resource;
    }

    private void addRoute(String from, String to, boolean collection, boolean listable) {
        String route = from + TestUtils.getHookRouteUrlSuffix();
        String target = to;
        String[] methods = new String[]{"GET", "PUT", "DELETE", "POST"};
        TestUtils.unregisterRoute(route);
        TestUtils.registerRoute(route, target, methods, null, collection, listable);
    }
}

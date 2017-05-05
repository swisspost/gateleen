package org.swisspush.gateleen.hook;

import com.jayway.restassured.RestAssured;
import com.jayway.restassured.response.Response;
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

import static com.jayway.restassured.RestAssured.*;

/**
 * Test class for the hook route feature.
 *
 * @author https://github.com/ljucam [Mario Ljuca]
 */
@RunWith(VertxUnitRunner.class)
public class RouteListingTest extends AbstractTest {
    private String requestUrlBase;
    private String targetUrlBase;
    private String parentKey;


    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath(SERVER_ROOT);

        parentKey = "routesource";
        requestUrlBase = "/tests/gateleen/" + parentKey;
        targetUrlBase = "http://localhost:" + MAIN_PORT + SERVER_ROOT + "/tests/gateleen/routetarget";
    }


    /**
     * Init the routing roules for the hooking and
     * prepare some static routes
     */
    private void initSettings() {
        // add a routing
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);
        rules = TestUtils.addRoutingRuleHooks(rules);
        TestUtils.putRoutingRules(rules);
    }

    /**
     * add some collections to the routesource
     */
    private void initTestCollections() {
        given().body("{ \"empty\" : \"empty\" }" ).put(requestUrlBase + "/1/resource").then().assertThat().statusCode(200);
        given().body("{ \"empty\" : \"empty\" }" ).put(requestUrlBase + "/2/resource").then().assertThat().statusCode(200);
        given().body("{ \"empty\" : \"empty\" }" ).put(requestUrlBase + "/3/resource").then().assertThat().statusCode(200);
    }


    @Test
    public void testListing(TestContext context) {
        Async async = context.async();
        delete();
        initSettings();
        initTestCollections();

        // Check if the returend array contains 1, 2, 3
        assertResponse(get(requestUrlBase), new String[]{"1/", "2/", "3/"});

        // add a listable route
        addRoute("ok", true, true);
        assertResponse(get(requestUrlBase), new String[]{"1/", "2/", "3/", "ok/"});

        // add another listable route
        addRoute("ok2", true, true);
        assertResponse(get(requestUrlBase), new String[]{"1/", "2/", "3/", "ok/", "ok2/"});

        // add a nonlistable route
        addRoute("nok/nok", true, true);
        assertResponse(get(requestUrlBase), new String[]{"1/", "2/", "3/", "ok/", "ok2/"});

        // add another nonlistable route => collection: false
        addRoute("nok2", false, true);
        assertResponse(get(requestUrlBase), new String[]{"1/", "2/", "3/", "ok/", "ok2/"});

        // add another nonlistable route => listable: false
        addRoute("nok3", true, false);
        assertResponse(get(requestUrlBase), new String[]{"1/", "2/", "3/", "ok/", "ok2/"});

        // add another nonlistable route => collection: false, listable: false
        addRoute("nok4", false, false);
        assertResponse(get(requestUrlBase), new String[]{"1/", "2/", "3/", "ok/", "ok2/"});

        // remove one listable route
        removeRoute("ok2");
        assertResponse(get(requestUrlBase), new String[]{"1/", "2/", "3/", "ok/"});

        // remove another listable route
        removeRoute("ok");
        assertResponse(get(requestUrlBase), new String[]{"1/", "2/", "3/"});

        // remove all routes
        removeRoute("ok");
        removeRoute("ok2");
        removeRoute("nok/nok");
        removeRoute("nok2");
        removeRoute("nok3");
        removeRoute("nok4");

        async.complete();
    }

    @Test
    public void testListingWithoutStaticCollections(TestContext context) {
        Async async = context.async();
        delete();
        initSettings();

        // nothing should be there (yet)
        get(requestUrlBase).then().assertThat().statusCode(404);

        // add a listable route
        addRoute("ok", true, true);
        assertResponse(get(requestUrlBase), new String[]{"ok/"});

        // add another listable route
        addRoute("ok2", true, true);
        assertResponse(get(requestUrlBase), new String[]{"ok/", "ok2/"});

        // add a nonlistable route
        addRoute("nok/nok", true, true);
        assertResponse(get(requestUrlBase), new String[]{"ok/", "ok2/"});

        // add another nonlistable route => collection: false
        addRoute("nok2", false, true);
        assertResponse(get(requestUrlBase), new String[]{"ok/", "ok2/"});

        // add another nonlistable route => listable: false
        addRoute("nok3", true, false);
        assertResponse(get(requestUrlBase), new String[]{"ok/", "ok2/"});

        // add another nonlistable route => collection: false, listable: false
        addRoute("nok4", false, false);
        assertResponse(get(requestUrlBase), new String[]{"ok/", "ok2/"});

        // remove all routes
        removeRoute("ok");
        removeRoute("ok2");
        removeRoute("nok/nok");
        removeRoute("nok2");
        removeRoute("nok3");
        removeRoute("nok4");

        async.complete();
    }


    @Test
    public void testListingWithoutStaticAndDynamicCollections(TestContext context) {
        Async async = context.async();
        delete();
        initSettings();

        // nothing should be there (yet)
        get(requestUrlBase).then().assertThat().statusCode(404);

        // add a nonlistable route
        addRoute("nok/nok", true, true);
        get(requestUrlBase).then().assertThat().statusCode(404);

        // add another nonlistable route => collection: false
        addRoute("nok2", false, true);
        get(requestUrlBase).then().assertThat().statusCode(404);

        // add another nonlistable route => listable: false
        addRoute("nok3", true, false);
        get(requestUrlBase).then().assertThat().statusCode(404);

        // add another nonlistable route => collection: false, listable: false
        addRoute("nok4", false, false);
        get(requestUrlBase).then().assertThat().statusCode(404);

        removeRoute("nok/nok");
        removeRoute("nok2");
        removeRoute("nok3");
        removeRoute("nok4");

        async.complete();
    }




    private void removeRoute(String name) {
        String route = requestUrlBase + "/" + name + TestUtils.getHookRouteUrlSuffix();
        TestUtils.unregisterRoute(route);
    }

    private void addRoute(String name, boolean collection, boolean listable) {
        String route = requestUrlBase + "/" + name + TestUtils.getHookRouteUrlSuffix();
        String target = targetUrlBase + "/" + name;
        String[] methods = new String[]{"GET", "PUT", "DELETE", "POST"};

        // just for security reasons (unregister route)
        TestUtils.unregisterRoute(route);

        TestUtils.registerRoute(route, target, methods, null, collection, listable);
   }


    private void assertResponse(final Response response, final String[] expectedArray) {
        Assert.assertEquals(200, response.statusCode());
        String bodyString = response.getBody().asString();
        System.out.println("BODY => " + bodyString + " <=");
        JsonObject body = new JsonObject(bodyString);
        JsonArray array = body.getJsonArray(parentKey);
        Assert.assertEquals(expectedArray.length, array.size());
        Assert.assertThat(array, Matchers.contains(expectedArray));
    }

}

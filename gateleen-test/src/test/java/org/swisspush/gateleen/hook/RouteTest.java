package org.swisspush.gateleen.hook;

import com.google.common.collect.ImmutableMap;
import org.awaitility.Awaitility;
import io.restassured.RestAssured;
import io.restassured.http.Header;
import io.restassured.http.Headers;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.*;
import static org.awaitility.Durations.TEN_SECONDS;
import static org.awaitility.Durations.TWO_SECONDS;
import static org.hamcrest.CoreMatchers.containsString;

/**
 * Test class for the hook route feature.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
@RunWith(VertxUnitRunner.class)
public class RouteTest extends AbstractTest {
    private String requestUrlBase;
    private String targetUrlBase;

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath(SERVER_ROOT);

        requestUrlBase = "/tests/gateleen/routesource";
        targetUrlBase = "http://localhost:" + MAIN_PORT + SERVER_ROOT + "/tests/gateleen/routetarget";
    }

    /**
     * Init the routing roules for the hooking.
     */
    private void initRoutingRules() {
        // add a routing
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);
        rules = TestUtils.addRoutingRuleHooks(rules);
        TestUtils.putRoutingRules(rules);
    }

    /**
     * Test for registration / unregistration. <br />
     */
    @Test
    public void testUnRegistration(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        // Settings
        String subresource = "registration";
        String routeName = "regtest";
        // -------

        String requestUrl = requestUrlBase + "/" + subresource + TestUtils.getHookRouteUrlSuffix();
        String target = targetUrlBase + "/" + routeName;
        String[] methods = new String[] { "GET", "PUT", "DELETE", "POST" };

        TestUtils.registerRoute(requestUrl, target, methods);
        TestUtils.unregisterRoute(requestUrl);

        async.complete();
    }

    /**
     * Tests if the staticHeaders are properly put as headers
     * to a routed request.
     *
     * @param context
     */
    @Test
    public void testRouteWithStaticHeaders(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        // Settings
        String subresource = "routeTest";
        // -------

        String requestUrl = requestUrlBase + "/" + subresource + TestUtils.getHookRouteUrlSuffix();
        String target = "http://localhost:" + MAIN_PORT + ROOT;
        String[] methods = new String[] { "GET", "PUT", "DELETE", "POST" };

        final String routedResource = requestUrlBase + "/" + subresource + "/debug";
        Map<String, String> staticHeaders = new LinkedHashMap<>();
        staticHeaders.put("x-test1", "1");
        staticHeaders.put("x-test2", "2");
        // -------

        // register route
        TestUtils.registerRoute(requestUrl, target, methods, staticHeaders);

        //
        Awaitility.given().await().atMost(TWO_SECONDS).untilAsserted(() ->
                when().get(routedResource).then().assertThat()
                        .statusCode(200)
                        .body(containsString("x-test1"))
                        .body(containsString("x-test2"))
        );
        // unregister route
        TestUtils.unregisterRoute(requestUrl);

        async.complete();
    }

    @Test
    public void testRouteWithMatchingHeadersFilter(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        // Settings
        String subresource = "routePathTest";
        String routeName = "routePathTest";
        // -------

        String requestUrl = requestUrlBase + "/" + subresource + TestUtils.getHookRouteUrlSuffix();
        String target = SERVER_ROOT + "/tests/gateleen/routetarget/" + routeName;
        String[] methods = new String[] { "GET", "PUT", "DELETE", "POST" };

        // just for security reasons (unregister route)
        delete(requestUrl);

        // -------

        final String routedResource = requestUrlBase + "/" + subresource + "/test";
        final String checkTarget = targetUrlBase + "/" + routeName + "/test";

        TestUtils.registerRoute(requestUrl, target, methods, null, true, false, null, "x-foo: (A|B)");

        String body2 = "{ \"name\" : \"routePathTest\"}";
        given().headers(Headers.headers(new Header("x-foo", "A"))).body(body2).put(routedResource).then().assertThat().statusCode(200);
        Awaitility.given().await().atMost(TEN_SECONDS).untilAsserted(() -> {
            given().headers(Headers.headers(new Header("x-foo", "A"))).when().get(routedResource).then().assertThat().body(containsString(body2));
            when().get(checkTarget).then().assertThat().body(containsString(body2));
        });

        async.complete();
    }

    @Test
    public void testRouteWithValidPath(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        // Settings
        String subresource = "routePathTest";
        String routeName = "routePathTest";
        // -------

        String requestUrl = requestUrlBase + "/" + subresource + TestUtils.getHookRouteUrlSuffix();
        String target = SERVER_ROOT + "/tests/gateleen/routetarget/" + routeName;
        String[] methods = new String[] { "GET", "PUT", "DELETE", "POST" };

        // just for security reasons (unregister route)
        delete(requestUrl);

        // -------

        final String routedResource = requestUrlBase + "/" + subresource + "/test";
        final String checkTarget = targetUrlBase + "/" + routeName + "/test";

        TestUtils.registerRoute(requestUrl, target, methods);

        String body2 = "{ \"name\" : \"routePathTest\"}";
        given().body(body2).put(routedResource).then().assertThat().statusCode(200);
        Awaitility.given().await().atMost(TWO_SECONDS).untilAsserted(() -> {
                    when().get(routedResource).then().assertThat().body(containsString(body2));
                    when().get(checkTarget).then().assertThat().body(containsString(body2));
        });

        async.complete();
    }

    /**
     * Test for create a route, and testing if requests
     * are rerouted to the new target.
     */
    @Test
    public void testRoute(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        // Settings
        String subresource = "routeTest";
        String routeName = "routeTest";
        // -------

        String requestUrl = requestUrlBase + "/" + subresource + TestUtils.getHookRouteUrlSuffix();
        String target = targetUrlBase + "/" + routeName;
        String[] methods = new String[] { "GET", "PUT", "DELETE", "POST" };

        // just for security reasons (unregister route)
        delete(requestUrl);

        // -------

        final String routedResource = requestUrlBase + "/" + subresource + "/test";
        final String checkTarget = targetUrlBase + "/" + routeName + "/test";

        /*
         * PUT something to the not yet routed resource.
         * -------
         */
        String body1 = "{ \"name\" : \"routeTest 1\"}";
        given().body(body1).put(routedResource).then().assertThat().statusCode(200);
        Awaitility.given().await().atMost(TWO_SECONDS).untilAsserted(() -> {
                    when().get(routedResource).then().assertThat().body(containsString(body1));
                    when().get(checkTarget).then().assertThat().statusCode(404);
        });
        delete(routedResource);

        // -------

        /*
         * Register a route
         * -------
         */
        TestUtils.registerRoute(requestUrl, target, methods);

        // -------

        /*
         * PUT something to the routed resource.
         * -------
         */
        String body2 = "{ \"name\" : \"routeTest 2\"}";
        given().body(body2).put(routedResource).then().assertThat().statusCode(200);
        Awaitility.given().await().atMost(TWO_SECONDS).untilAsserted(() -> {
            when().get(routedResource).then().assertThat().body(containsString(body2));
            when().get(checkTarget).then().assertThat().body(containsString(body2));
        });

        // -------

        /*
         * DELETE something from the routed resource.
         * -------
         */
        delete(routedResource).then().assertThat().statusCode(200);
        Awaitility.given().await().atMost(TWO_SECONDS).untilAsserted(() -> {
                    when().get(routedResource).then().assertThat().statusCode(404);
                    when().get(checkTarget).then().assertThat().statusCode(404);
        });

        // -------

        /*
         * Unregister a route
         * -------
         */
        TestUtils.unregisterRoute(requestUrl);

        // -------

        /*
         * PUT something to the no longer routed resource.
         * -------
         */
        String body3 = "{ \"name\" : \"routeTest 3\"}";
        given().body(body3).put(routedResource).then().assertThat().statusCode(200);
        Awaitility.given().await().atMost(TWO_SECONDS).untilAsserted(() -> {
                    when().get(routedResource).then().assertThat().body(containsString(body3));
                    when().get(checkTarget).then().assertThat().statusCode(404);
                    delete(routedResource);
        });

        // -------
        async.complete();
    }

    /**
     * Tests if the http response code is translated when translateStatus property is defined
     *
     * @param context
     */
    @Test
    public void testRouteWithTranslateStatus(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        // Settings
        String subresource = "routeTest";
        // -------

        String requestUrl = requestUrlBase + "/" + subresource + TestUtils.getHookRouteUrlSuffix();
        String target = "http://localhost:" + MAIN_PORT + ROOT + "/server/tests/res_1";
        String[] methods = new String[] { "GET", "PUT", "DELETE", "POST" };

        final String routedResource = requestUrlBase + "/" + subresource + "/debug";

        // -------

        // register route (without translateStatus)
        TestUtils.registerRoute(requestUrl, target, methods);

        // since no translateStatus was defined, the resource should not be found and return a 404
        Awaitility.given().await().atMost(TWO_SECONDS).untilAsserted(() ->
                when().get(routedResource).then().assertThat()
                        .statusCode(404)
        );

        Map<Pattern, Integer> translateStatus = ImmutableMap.of(Pattern.compile("404"), 405);

        // replace route (with translateStatus)
        TestUtils.registerRoute(requestUrl, target, methods, null, true, false, translateStatus);

        // with translateStatus defined, the response status code should have changed from 404 to 405
        Awaitility.given().await().atMost(TWO_SECONDS).untilAsserted(() ->
                when().get(routedResource).then().assertThat()
                        .statusCode(405)
        );

        // unregister route
        TestUtils.unregisterRoute(requestUrl);

        async.complete();
    }

    /**
     * Tests that path suffixes are appended to the destination when routing requests.
     * This verifies the current behavior described in GitHub issue #758.
     * <p>
     * When a route hook is registered at path A with destination B, and a request
     * comes in to A/suffix, the request is forwarded to B/suffix.
     */
    @Test
    public void testRoutePathSuffixIsAppendedToDestination(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        String subresource = "suffixTest";
        String routeName = "suffixTestTarget";

        String requestUrl = requestUrlBase + "/" + subresource + TestUtils.getHookRouteUrlSuffix();
        String target = targetUrlBase + "/" + routeName;
        String[] methods = new String[] { "GET", "PUT", "DELETE", "POST" };

        delete(requestUrl);

        final String routedResourceWithSuffix = requestUrlBase + "/" + subresource + "/deep/nested/path";
        final String expectedTargetWithSuffix = targetUrlBase + "/" + routeName + "/deep/nested/path";

        TestUtils.registerRoute(requestUrl, target, methods);

        String body = "{ \"name\" : \"suffix test data\"}";
        given().body(body).put(routedResourceWithSuffix).then().assertThat().statusCode(200);

        Awaitility.given().await().atMost(TWO_SECONDS).untilAsserted(() -> {
            when().get(routedResourceWithSuffix).then().assertThat().body(containsString(body));
            when().get(expectedTargetWithSuffix).then().assertThat().body(containsString(body));
        });

        TestUtils.unregisterRoute(requestUrl);
        async.complete();
    }

    /**
     * Tests that a single path segment suffix is appended to the destination.
     * Simulates the FCM scenario from GitHub issue #758 where a device token
     * is passed as a path segment.
     */
    @Test
    public void testRouteSingleSegmentSuffixIsAppended(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        String subresource = "fcmSimulation";
        String routeName = "messagesEndpoint";

        String requestUrl = requestUrlBase + "/" + subresource + TestUtils.getHookRouteUrlSuffix();
        String target = targetUrlBase + "/" + routeName;
        String[] methods = new String[] { "GET", "PUT", "DELETE", "POST" };

        delete(requestUrl);

        final String routedResourceWithToken = requestUrlBase + "/" + subresource + "/device-token-abc123";
        final String expectedTargetWithToken = targetUrlBase + "/" + routeName + "/device-token-abc123";

        TestUtils.registerRoute(requestUrl, target, methods);

        String body = "{ \"message\" : \"push notification\"}";
        given().body(body).put(routedResourceWithToken).then().assertThat().statusCode(200);

        Awaitility.given().await().atMost(TWO_SECONDS).untilAsserted(() -> {
            when().get(routedResourceWithToken).then().assertThat().body(containsString(body));
            when().get(expectedTargetWithToken).then().assertThat().body(containsString(body));
        });

        TestUtils.unregisterRoute(requestUrl);
        async.complete();
    }

    /**
     * Tests that with fullUrl=true, requests are forwarded to the exact destination URL
     * without appending the path suffix. This is the feature from GitHub issue #758.
     * <p>
     * When a route hook is registered at path A with destination B and fullUrl=true,
     * a request to A/suffix is forwarded to B (not B/suffix).
     */
    @Test
    public void testRouteWithFullUrlTrue(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        String subresource = "fullUrlTest";
        String routeName = "fullUrlTarget";

        String requestUrl = requestUrlBase + "/" + subresource + TestUtils.getHookRouteUrlSuffix();
        String target = targetUrlBase + "/" + routeName;
        String[] methods = new String[] { "GET", "PUT", "DELETE", "POST" };

        delete(requestUrl);

        String body = "{ \"name\" : \"fullUrl test data\"}";
        given().body(body).put(target).then().assertThat().statusCode(200);

        TestUtils.registerRoute(requestUrl, target, methods, null, true, false, null, true);

        final String routedResourceWithSuffix = requestUrlBase + "/" + subresource + "/deep/nested/path";

        Awaitility.given().await().atMost(TWO_SECONDS).untilAsserted(() -> {
            when().get(routedResourceWithSuffix).then().assertThat()
                    .statusCode(200)
                    .body(containsString(body));
        });

        final String targetWithSuffix = targetUrlBase + "/" + routeName + "/deep/nested/path";
        when().get(targetWithSuffix).then().assertThat().statusCode(404);

        TestUtils.unregisterRoute(requestUrl);
        async.complete();
    }

    /**
     * Tests that with fullUrl=false (explicit), path suffix is still appended to destination.
     * This verifies backward compatibility when fullUrl is explicitly set to false.
     */
    @Test
    public void testRouteWithFullUrlFalseExplicit(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        String subresource = "fullUrlFalseTest";
        String routeName = "fullUrlFalseTarget";

        String requestUrl = requestUrlBase + "/" + subresource + TestUtils.getHookRouteUrlSuffix();
        String target = targetUrlBase + "/" + routeName;
        String[] methods = new String[] { "GET", "PUT", "DELETE", "POST" };

        delete(requestUrl);

        final String routedResourceWithSuffix = requestUrlBase + "/" + subresource + "/sub/path";
        final String expectedTargetWithSuffix = target + "/sub/path";

        TestUtils.registerRoute(requestUrl, target, methods, null, true, false, null, false);

        String body = "{ \"name\" : \"fullUrl false test data\"}";
        given().body(body).put(routedResourceWithSuffix).then().assertThat().statusCode(200);

        Awaitility.given().await().atMost(TWO_SECONDS).untilAsserted(() -> {
            when().get(routedResourceWithSuffix).then().assertThat().body(containsString(body));
            when().get(expectedTargetWithSuffix).then().assertThat().body(containsString(body));
        });

        TestUtils.unregisterRoute(requestUrl);
        async.complete();
    }
}

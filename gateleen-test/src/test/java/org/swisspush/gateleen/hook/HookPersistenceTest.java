package org.swisspush.gateleen.hook;

import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;
import com.jayway.awaitility.Duration;
import io.restassured.RestAssured;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import static com.jayway.awaitility.Awaitility.await;
import static io.restassured.RestAssured.*;
import static org.hamcrest.CoreMatchers.equalTo;

/**
 * A test class for the hook storage.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
@RunWith(VertxUnitRunner.class)
public class HookPersistenceTest extends AbstractTest {
    private String requestUrlBase;
    private String resourceStorageBase;
    private static final String HOOKS_LISTENERS_URI_PART = "/_hooks/listeners/";

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath(SERVER_ROOT);

        requestUrlBase = "http://localhost:" + MAIN_PORT;
        resourceStorageBase = "http://localhost:" + MAIN_PORT;
    }

    /**
     * Init the routing rules for the hooking.
     */
    private void initRoutingRules() {
        // add a routing
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);
        rules = TestUtils.addRoutingRuleHooks(rules);
        TestUtils.putRoutingRules(rules);
    }

    /**
     * Tests if the listener hooks are stored
     * properly.
     */
    @Test
    public void testHookStorageListeners(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        System.out.println("testHookStorageListener");

        /*
         * Reset RestAssured settings
         * enable logging
         * disable urlEncoding, this is necessary for
         * the storage url, which contains "+".
         */
        RestAssured.reset();
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        RestAssured.urlEncodingEnabled = false;

        String fakeTarget = "http://testmy/HookPersistenceTest";
        int requestExpireTime = 4; // 4 seconds

        /*
         * Register a few listeners (3).
         */
        String listener1 = requestUrlBase + SERVER_ROOT + "/tests/gateleen" + "/hookPersistanceTest" + TestUtils.getHookListenersUrlSuffix() + "service1/1";
        String listener2 = requestUrlBase + SERVER_ROOT + "/tests/gateleen" + "/hookPersistanceTest" + TestUtils.getHookListenersUrlSuffix() + "service2/2";
        String listener3 = requestUrlBase + SERVER_ROOT + "/tests/gateleen" + "/hookPersistanceTest" + TestUtils.getHookListenersUrlSuffix() + "service3/3";
        String request1 = resourceStorageBase + SERVER_ROOT + "/hooks/v1/registrations/listeners/" + getUniqueListenerId(listener1);
        String request2 = resourceStorageBase + SERVER_ROOT + "/hooks/v1/registrations/listeners/" + getUniqueListenerId(listener2);
        String request3 = resourceStorageBase + SERVER_ROOT + "/hooks/v1/registrations/listeners/" + getUniqueListenerId(listener3);

        unregisterHook(listener1);
        unregisterHook(listener2);
        unregisterHook(listener3);

        /*
         * Check if no hook is stored
         */
        when().get(request1).then().assertThat().statusCode(404);
        when().get(request2).then().assertThat().statusCode(404);
        when().get(request3).then().assertThat().statusCode(404);

        /*
         * Register Listener
         * TTL of every resource is 120s.
         */
        registerHook(listener1, fakeTarget, null, requestExpireTime, 120);
        registerHook(listener2, fakeTarget, null, requestExpireTime, 120);
        registerHook(listener3, fakeTarget, null, requestExpireTime, 120);

        TestUtils.waitSomeTime(5);

        /*
         * Check if they are stored
         * http://localhost:8989/server/hooks/v1/registrations/listeners/http+service1+1
         */
        checkGETStatusCodeWithAwait(request1, 200);
        checkGETStatusCodeWithAwait(request2, 200);
        checkGETStatusCodeWithAwait(request3, 200);

        /*
         * Delete listeners
         */
        unregisterHook(listener1);
        unregisterHook(listener2);
        unregisterHook(listener3);

        /*
         * Check if no hook is stored
         */
        checkGETStatusCodeWithAwait(request1, 404);
        checkGETStatusCodeWithAwait(request2, 404);
        checkGETStatusCodeWithAwait(request3, 404);

        async.complete();
    }

    /**
     * Tests if the route hooks are stored
     * properly.
     */
    @Test
    public void testHookStorageRoutes(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        System.out.println("testHookStorageRoute");

        /*
         * Reset RestAssured settings
         * enable logging
         * disable urlEncoding, this is necessary for
         * the storage url, which contains "+".
         */
        RestAssured.reset();
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        RestAssured.urlEncodingEnabled = false;

        String fakeTarget = "http://testmy/HookPersistenceTest";
        int requestExpireTime = 4; // 4 seconds

        /*
         * Register a few routes (3).
         */
        String route1 = requestUrlBase + SERVER_ROOT + "/tests/gateleen" + "/hookPersistanceTest1" + TestUtils.getHookRouteUrlSuffix();
        String route2 = requestUrlBase + SERVER_ROOT + "/tests/gateleen" + "/hookPersistanceTest2" + TestUtils.getHookRouteUrlSuffix();
        String route3 = requestUrlBase + SERVER_ROOT + "/tests/gateleen" + "/hookPersistanceTest3" + TestUtils.getHookRouteUrlSuffix();

        String request1 = resourceStorageBase + SERVER_ROOT + "/hooks/v1/registrations/routes/" + getRouteStorageId(route1);
        String request2 = resourceStorageBase + SERVER_ROOT + "/hooks/v1/registrations/routes/" + getRouteStorageId(route2);
        String request3 = resourceStorageBase + SERVER_ROOT + "/hooks/v1/registrations/routes/" + getRouteStorageId(route3);

        unregisterHook(route1);
        unregisterHook(route2);
        unregisterHook(route3);

        /*
         * Check if no hook is stored
         */
        when().get(route1).then().assertThat().statusCode(404);
        when().get(route2).then().assertThat().statusCode(404);
        when().get(route3).then().assertThat().statusCode(404);

        /*
         * Register routes
         * TTL of every resource is 120s.
         */
        registerHook(route1, fakeTarget, null, requestExpireTime, 120);
        registerHook(route2, fakeTarget, null, requestExpireTime, 120);
        registerHook(route3, fakeTarget, null, requestExpireTime, 120);

        TestUtils.waitSomeTime(2);

        /*
         * Check if they are stored
         * http://localhost:8989/server/hooks/v1/registrations/routes/+gateleen+server+hookPersistanceTest1
         */
        checkGETStatusCodeWithAwait(request1, 200);
        checkGETStatusCodeWithAwait(request2, 200);
        checkGETStatusCodeWithAwait(request3, 200);

        /*
         * Delete routes
         */
        unregisterHook(route1);
        unregisterHook(route2);
        unregisterHook(route3);

        /*
         * Check if no hook is stored
         */
        checkGETStatusCodeWithAwait(request1, 404);
        checkGETStatusCodeWithAwait(request2, 404);
        checkGETStatusCodeWithAwait(request3, 404);

        async.complete();
    }

    /**
     * Tests if the hooks are cleand up
     * properly after the given time.
     */
    @Test
    public void testHookCleanupListeners(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        System.out.println("testHookCleanupListeners");

        /*
         * Reset RestAssured settings
         * enable logging
         * disable urlEncoding, this is necessary for
         * the storage url, which contains "+".
         */
        RestAssured.reset();
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        RestAssured.urlEncodingEnabled = false;

        String fakeTarget = "http://testmy/HookPersistenceTest";
        int requestExpireTime = 4; // 4 seconds

        /*
         * Register a few listeners (two are enough).
         */
        String listener1 = requestUrlBase + SERVER_ROOT + "/tests/gateleen" + "/hookPersistanceTest" + TestUtils.getHookListenersUrlSuffix() + "service11/1";
        String listener2 = requestUrlBase + SERVER_ROOT + "/tests/gateleen" + "/hookPersistanceTest" + TestUtils.getHookListenersUrlSuffix() + "service12/2";
        String request1 = resourceStorageBase + SERVER_ROOT + "/hooks/v1/registrations/listeners/" + getUniqueListenerId(listener1);
        String request2 = resourceStorageBase + SERVER_ROOT + "/hooks/v1/registrations/listeners/" + getUniqueListenerId(listener2);
        System.out.println(request1);

        unregisterHook(listener1);
        unregisterHook(listener2);

        /*
         * Check if no hook is stored
         */
        when().get(request1).then().assertThat().statusCode(404);
        when().get(request2).then().assertThat().statusCode(404);

        /*
         * Register Listener
         * 1 - TTL 8
         * 2 - TTL 16
         */
        registerHook(listener1, fakeTarget, null, requestExpireTime, 8);
        registerHook(listener2, fakeTarget, null, requestExpireTime, 16);

        TestUtils.waitSomeTime(2);

        /*
         * both must be stored
         */
        checkGETStatusCodeWithAwait(request1, 200);
        checkGETStatusCodeWithAwait(request2, 200);

        // wait 8 s
        TestUtils.waitSomeTime(8);

        /*
         * only one must be stored
         */
        checkGETStatusCodeWithAwait(request1, 404);
        checkGETStatusCodeWithAwait(request2, 200);

        // wait again 8 s
        TestUtils.waitSomeTime(8);

        /*
         * none must be stored
         */
        checkGETStatusCodeWithAwait(request1, 404);
        checkGETStatusCodeWithAwait(request2, 404);

        async.complete();
    }

    /**
     * Tests if the hooks are cleand up
     * properly after the given time.
     */
    @Test
    public void testHookCleanupRoutes(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        System.out.println("testHookCleanupRoutes");

        /*
         * Reset RestAssured settings
         * enable logging
         * disable urlEncoding, this is necessary for
         * the storage url, which contains "+".
         */
        RestAssured.reset();
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        RestAssured.urlEncodingEnabled = false;

        String fakeTarget = "http://testmy/HookPersistenceTest";
        int requestExpireTime = 4; // 4 seconds

        /*
         * Register a few routes (two are enough).
         */
        String route1 = requestUrlBase + SERVER_ROOT + "/tests/gateleen" + "/hookPersistanceTest11" + TestUtils.getHookRouteUrlSuffix();
        String route2 = requestUrlBase + SERVER_ROOT + "/tests/gateleen" + "/hookPersistanceTest12" + TestUtils.getHookRouteUrlSuffix();

        String request1 = resourceStorageBase + SERVER_ROOT + "/hooks/v1/registrations/routes/" + getRouteStorageId(route1);
        String request2 = resourceStorageBase + SERVER_ROOT + "/hooks/v1/registrations/routes/" + getRouteStorageId(route2);

        unregisterHook(route1);
        unregisterHook(route2);

        /*
         * Check if no hook is stored
         */
        when().get(request1).then().assertThat().statusCode(404);
        when().get(request2).then().assertThat().statusCode(404);

        /*
         * Register Listener
         * 1 - TTL 8
         * 2 - TTL 16
         */
        registerHook(route1, fakeTarget, null, requestExpireTime,  8);
        registerHook(route2, fakeTarget, null, requestExpireTime, 16);

        TestUtils.waitSomeTime(2);

        /*
         * both must be stored
         */
        checkGETStatusCodeWithAwait(request1, 200);
        checkGETStatusCodeWithAwait(request2, 200);

        // wait 8 s
        TestUtils.waitSomeTime(8);

        /*
         * only one must be stored
         */
        checkGETStatusCodeWithAwait(request1, 404);
        checkGETStatusCodeWithAwait(request2, 200);

        // wait another 8 s
        TestUtils.waitSomeTime(8);

        /*
         * none must be stored
         */
        checkGETStatusCodeWithAwait(request1, 404);
        checkGETStatusCodeWithAwait(request2, 404);

        async.complete();
    }

    /**
     * Checks if the GET request for the
     * resource gets a response with
     * the given status code.
     * 
     * @param request
     * @param statusCode
     */
    private void checkGETStatusCodeWithAwait(final String request, final Integer statusCode) {
        await().atMost(Duration.FIVE_SECONDS).until(() -> {
            System.out.println(request);
            return String.valueOf(RestAssured.get(request).getStatusCode());
        }, equalTo(String.valueOf(statusCode)));
    }

    /**
     * Registers a hook.
     *
     * @param requestUrl
     * @param target
     * @param methods
     * @param expireAfter
     * @param resourceExpireTime of hook resource
     */
    private void registerHook(final String requestUrl, final String target, String[] methods, Integer expireAfter, int resourceExpireTime) {
        String body = "{ \"destination\":\"" + target + "\"";

        String m = null;
        if (methods != null) {
            for (String method : methods) {
                m += "\"" + method + "\", ";
            }
            m = m.endsWith(", ") ? m.substring(0, m.lastIndexOf(",")) : m;
            m = "\"methods\": [" + m + "]";
        }
        body += expireAfter != null ? ", \"expireAfter\" : " + expireAfter : "";
        body = body + "}";

        with().body(body).header("x-expire-after", String.valueOf(resourceExpireTime)).put(requestUrl).then().assertThat().statusCode(200);
    }

    /**
     * Creates a listener id, which is unique for the given service, and the
     * monitored url.
     * 
     * @param requestUrl
     * @return
     */
    private String getUniqueListenerId(String requestUrl) {
        StringBuffer listenerId = new StringBuffer();

        // eg. http/colin/1 -> http+colin+1
        listenerId.append(convertToStoragePattern(getListenerUrlSegment(requestUrl)));

        // eg. /gateleen/trip/v1 -> +gateleen+trip+v1
        listenerId.append(convertToStoragePattern(getMonitoredUrlSegment(requestUrl)));

        return listenerId.toString();
    }

    /**
     * Replaces all unwanted characters (like "/", ".", ":") with "+".
     * 
     * @param urlSegment
     * @return String
     */
    private String convertToStoragePattern(String urlSegment) {
        return urlSegment.replace(requestUrlBase, "").replace("/", "+").replace(".", "+").replace(":", "+");
    }

    /**
     * Returns the url segment to which the listener should be hooked. <br />
     * For "http://a/b/c/_hooks/listeners/http/colin/1234578" this would
     * be "http://a/b/c".
     * 
     * @param requestUrl
     * @return url segment to which the listener should be hooked.
     */
    private String getMonitoredUrlSegment(String requestUrl) {
        return requestUrl.substring(0, requestUrl.indexOf(HOOKS_LISTENERS_URI_PART));
    }

    /**
     * Returns the url segment which represents the listener. <br />
     * For "http://a/b/c/_hooks/listeners/http/colin/1234578" this would
     * be "http/colin/1234578".
     * 
     * @param requestUrl
     * @return url segment
     */
    private String getListenerUrlSegment(String requestUrl) {
        String segment = requestUrl.substring(requestUrl.indexOf(HOOKS_LISTENERS_URI_PART));

        // remove hook - part
        segment = segment.replace(HOOKS_LISTENERS_URI_PART, "");

        return segment;
    }

    /**
     * Storage id of a route.
     * 
     * @param requestUrl
     * @return
     */
    private String getRouteStorageId(String requestUrl) {
        String url = requestUrl.replace(requestUrlBase, "");
        return url.substring(0, url.indexOf(TestUtils.getHookRouteUrlSuffix())).replace("/", "+") + "+_hooks+route";
    }

    /**
     * Unregisters a hook.
     *
     * @param request
     */
    private void unregisterHook(String request) {
        delete(request).then().assertThat().statusCode(200);
    }
}

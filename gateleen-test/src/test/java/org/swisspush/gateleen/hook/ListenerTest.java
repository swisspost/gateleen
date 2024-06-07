package org.swisspush.gateleen.hook;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.google.common.collect.ImmutableMap;
import io.restassured.RestAssured;
import io.restassured.http.Header;
import io.restassured.http.Headers;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.awaitility.Awaitility;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static io.restassured.RestAssured.*;
import static org.awaitility.Awaitility.await;
import static org.awaitility.Durations.FIVE_SECONDS;
import static org.awaitility.Durations.TEN_SECONDS;
import static org.hamcrest.CoreMatchers.*;



/**
 * Test class for the hook listener feature.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
@RunWith(VertxUnitRunner.class)
public class ListenerTest extends AbstractTest {
    private final static int WIREMOCK_PORT = 8881;
    private String requestUrlBase;
    private String targetUrlBase;

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(WIREMOCK_PORT);

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath(SERVER_ROOT);

        requestUrlBase = "/tests/gateleen/monitoredresource";
        targetUrlBase = "http://localhost:" + MAIN_PORT + SERVER_ROOT + "/tests/gateleen/targetresource";
    }

    /**
     * Init the routing roules for the hooking.
     */
    private void initRoutingRules() {
        // add a routing
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);
        rules = TestUtils.addRoutingRuleQueuing(rules);
        rules = TestUtils.addRoutingRuleHooks(rules);
        TestUtils.putRoutingRules(rules);
    }

    /**
     * Test for registration / unregistration. <br />
     * eg. register / unregister: http://localhost:7012/gateleen/server/listenertest/registration/_hooks/listeners/regtest/1
     */
    @Test
    public void testUnRegistration(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        // Settings
        String subresource = "registration";
        String listenerNo = "1";
        String listenerName = "regtest";
        // -------

        String requestUrl = requestUrlBase + "/" + subresource + TestUtils.getHookListenersUrlSuffix() + listenerName + "/" + listenerNo;
        String target = targetUrlBase + "/" + listenerName;
        String[] methods = new String[]{"GET", "PUT", "DELETE", "POST"};

        TestUtils.registerListener(requestUrl, target, methods, null);
        TestUtils.unregisterListener(requestUrl);

        async.complete();
    }

    /**
     * Test for one listener. <br />
     * eg. register / unregister: http://localhost:7012/gateleen/server/listenertest/fwOneListener/_hooks/listeners/fwOneListener/1 <br />
     * requestUrl: http://localhost:7012/gateleen/server/listenertest/fwOneListener/test
     */
    @Test
    public void testRequestForwardingForOneListener(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        // Settings
        String subresource = "fwOneListener";
        String listenerNo = "1";
        String listenerName = "fwOneListener";
        // -------

        String registerUrl = requestUrlBase + "/" + subresource + TestUtils.getHookListenersUrlSuffix() + listenerName + "/" + listenerNo;
        String target = targetUrlBase + "/" + listenerName;
        String[] methods = new String[]{"GET", "PUT", "DELETE", "POST"};

        final String requestUrl = requestUrlBase + "/" + subresource + "/" + "test";
        final String targetUrl = target + "/" + "test";
        final String body = "{ \"name\" : \"" + subresource + "\"}";

        delete(requestUrl);
        delete(targetUrl);

        // register a listener
        TestUtils.registerListener(registerUrl, target, methods);

        // send request
        checkPUTStatusCode(requestUrl, body, 200);

        // check if request is processd (server)
        checkGETBodyWithAwait(requestUrl, body);

        // check if request is processd (resource storage)
        checkGETBodyWithAwait(targetUrl, body);

        // delete resource
        checkDELETEStatusCode(requestUrl, 200);

        // check if request is processd (server)
        checkGETStatusCodeWithAwait(requestUrl, 404);

        // check if request is processd (resource storage)
        checkGETStatusCodeWithAwait(targetUrl, 404);

        TestUtils.unregisterListener(registerUrl);

        async.complete();
    }

    /**
     * Test for two listener monitoring the same resource. <br />
     * eg. register / unregister: http://localhost:7012/gateleen/server/listenertest/fwTwoListener/_hooks/listeners/firstListener/1 <br />
     * register / unregister: http://localhost:7012/gateleen/server/listenertest/fwTwoListener/_hooks/listeners/secondListener/2 <br />
     * requestUrl: http://localhost:7012/gateleen/server/listenertest/fwTwoListener/test
     */
    @Test
    public void testRequestForwardingForTwoListenerAtSameResource(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        // Settings
        String subresource = "fwTwoListener";
        String listenerNo = "1";
        String listenerName = "firstListener";

        String registerUrlListener1 = requestUrlBase + "/" + subresource + TestUtils.getHookListenersUrlSuffix() + listenerName + "/" + listenerNo;
        String targetListener1 = targetUrlBase + "/" + listenerName;
        String[] methodsListener1 = new String[]{"PUT", "DELETE", "POST"};
        final String targetUrlListener1 = targetUrlBase + "/" + listenerName + "/" + "test";

        listenerNo = "2";
        listenerName = "secondListener";

        String registerUrlListener2 = requestUrlBase + "/" + subresource + TestUtils.getHookListenersUrlSuffix() + listenerName + "/" + listenerNo;
        String targetListener2 = targetUrlBase + "/" + listenerName;
        String[] methodsListener2 = new String[]{"PUT", "DELETE", "POST"};
        final String targetUrlListener2 = targetUrlBase + "/" + listenerName + "/" + "test";
        // -------

        final String requestUrl = requestUrlBase + "/" + subresource + "/" + "test";
        final String body = "{ \"name\" : \"" + subresource + "\"}";

        delete(requestUrl);
        delete(targetUrlListener1);
        delete(targetUrlListener2);

        /*
         * Sending request, listener not yet hooked
         */
        checkPUTStatusCode(requestUrl, body, 200);
        checkGETStatusCodeWithAwait(requestUrl, 200);
        checkGETStatusCodeWithAwait(targetUrlListener1, 404);
        checkGETStatusCodeWithAwait(targetUrlListener2, 404);
        checkDELETEStatusCode(requestUrl, 200);
        checkGETStatusCodeWithAwait(requestUrl, 404);

        /*
         * Sending request, one listener hooked
         */
        TestUtils.registerListener(registerUrlListener1, targetListener1, methodsListener1);

        checkPUTStatusCode(requestUrl, body, 200);
        checkGETStatusCodeWithAwait(requestUrl, 200);
        checkGETBodyWithAwait(targetUrlListener1, body);
        checkGETStatusCodeWithAwait(targetUrlListener2, 404);
        checkDELETEStatusCode(requestUrl, 200);
        checkGETStatusCodeWithAwait(requestUrl, 404);
        checkGETStatusCodeWithAwait(targetUrlListener1, 404);

        TestUtils.unregisterListener(registerUrlListener1);

        /*
         * Sending request, both listener hooked
         */
        TestUtils.registerListener(registerUrlListener1, targetListener1, methodsListener1);
        TestUtils.registerListener(registerUrlListener2, targetListener2, methodsListener2);

        checkPUTStatusCode(requestUrl, body, 200);
        checkGETStatusCodeWithAwait(requestUrl, 200);
        checkGETBodyWithAwait(targetUrlListener1, body);
        checkGETBodyWithAwait(targetUrlListener2, body);
        checkDELETEStatusCode(requestUrl, 200);
        checkGETStatusCodeWithAwait(requestUrl, 404);
        checkGETStatusCodeWithAwait(targetUrlListener1, 404);
        checkGETStatusCodeWithAwait(targetUrlListener2, 404);

        TestUtils.unregisterListener(registerUrlListener1);
        TestUtils.unregisterListener(registerUrlListener2);

        async.complete();
    }

    /**
     * Test for two listeners monitoring a different resource. <br />
     * eg. register / unregister: http://localhost:7012/gateleen/server/listenertest/fwTwoDifferentListener/_hooks/listeners/firstDiffListener/1 <br />
     * register / unregister: http://localhost:7012/gateleen/server/listenertest/fwTwoDifferentListener/subres/_hooks/listeners/secondDiffListener/2 <br />
     * requestUrl1: http://localhost:7012/gateleen/server/listenertest/fwTwoDifferentListener/test <br />
     * requestUrl2: http://localhost:7012/gateleen/server/listenertest/fwTwoDifferentListener/subres/test <br />
     */
    @Test
    public void testRequestForwardingForTwoListenerAtDifferentResource(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        // Settings
        String subresource = "fwTwoDifferentListener";
        String additionalSubResource = "subres";
        String listenerNo = "1";
        String listenerName = "firstDiffListener";

        String registerUrlListener1 = requestUrlBase + "/" + subresource + TestUtils.getHookListenersUrlSuffix() + listenerName + "/" + listenerNo;
        String targetListener1 = targetUrlBase + "/" + listenerName;
        String[] methodsListener1 = new String[]{"PUT", "DELETE", "POST"};
        final String masterTargetUrlListener1 = targetUrlBase + "/" + listenerName + "/" + "test";
        final String slaveTargetUrlListener1 = targetUrlBase + "/" + listenerName + "/" + additionalSubResource + "/" + "test";

        listenerNo = "2";
        listenerName = "secondDiffListener";

        String registerUrlListener2 = requestUrlBase + "/" + subresource + "/" + additionalSubResource + TestUtils.getHookListenersUrlSuffix() + listenerName + "/" + listenerNo;
        String targetListener2 = targetUrlBase + "/" + listenerName;
        String[] methodsListener2 = new String[]{"PUT", "DELETE", "POST"};
        final String slaveTargetUrlListener2 = targetUrlBase + "/" + listenerName + "/" + "test";
        // -------

        // just for security reasons
        TestUtils.unregisterListener(registerUrlListener1);
        TestUtils.unregisterListener(registerUrlListener2);

        final String requestUrlMaster = requestUrlBase + "/" + subresource + "/" + "test";
        final String requestUrlSlave = requestUrlBase + "/" + subresource + "/" + additionalSubResource + "/" + "test";

        final String body = "{ \"name\" : \"" + subresource + "\"}";

        delete(requestUrlMaster);
        delete(requestUrlSlave);
        delete(masterTargetUrlListener1);
        delete(slaveTargetUrlListener1);
        delete(slaveTargetUrlListener2);

        /*
         * Sending request, listener not yet hooked
         */
        checkPUTStatusCode(requestUrlMaster, body, 200);
        checkGETStatusCodeWithAwait(requestUrlMaster, 200);
        checkGETStatusCodeWithAwait(masterTargetUrlListener1, 404);
        checkGETStatusCodeWithAwait(slaveTargetUrlListener2, 404);
        checkDELETEStatusCode(requestUrlMaster, 200);
        checkGETStatusCodeWithAwait(requestUrlMaster, 404);

        checkPUTStatusCode(requestUrlSlave, body, 200);
        checkGETStatusCodeWithAwait(requestUrlSlave, 200);
        checkGETStatusCodeWithAwait(masterTargetUrlListener1, 404);
        checkGETStatusCodeWithAwait(slaveTargetUrlListener2, 404);
        checkDELETEStatusCode(requestUrlSlave, 200);
        checkGETStatusCodeWithAwait(requestUrlSlave, 404);

        /*
         * Sending request, master listener hooked
         */
        TestUtils.registerListener(registerUrlListener1, targetListener1, methodsListener1);

        checkPUTStatusCode(requestUrlMaster, body, 200);
        checkGETStatusCodeWithAwait(requestUrlMaster, 200);
        checkGETBodyWithAwait(masterTargetUrlListener1, body);
        checkGETStatusCodeWithAwait(slaveTargetUrlListener2, 404);
        checkDELETEStatusCode(requestUrlMaster, 200);
        checkGETStatusCodeWithAwait(requestUrlMaster, 404);
        checkGETStatusCodeWithAwait(masterTargetUrlListener1, 404);

        checkPUTStatusCode(requestUrlSlave, body, 200);
        checkGETStatusCodeWithAwait(requestUrlSlave, 200);
        checkGETBodyWithAwait(slaveTargetUrlListener1, body);
        checkGETStatusCodeWithAwait(slaveTargetUrlListener2, 404);
        checkDELETEStatusCode(requestUrlSlave, 200);
        checkGETStatusCodeWithAwait(requestUrlSlave, 404);
        checkGETStatusCodeWithAwait(slaveTargetUrlListener1, 404);

        TestUtils.unregisterListener(registerUrlListener1);

        /*
         * Sending request, both listener hooked
         */
        TestUtils.registerListener(registerUrlListener1, targetListener1, methodsListener1);
        TestUtils.registerListener(registerUrlListener2, targetListener2, methodsListener2);

        checkPUTStatusCode(requestUrlMaster, body, 200);
        checkGETStatusCodeWithAwait(requestUrlMaster, 200);
        checkGETBodyWithAwait(masterTargetUrlListener1, body);
        checkGETStatusCodeWithAwait(slaveTargetUrlListener2, 404);
        checkDELETEStatusCode(requestUrlMaster, 200);
        checkGETStatusCodeWithAwait(requestUrlMaster, 404);
        checkGETStatusCodeWithAwait(masterTargetUrlListener1, 404);
        checkGETStatusCodeWithAwait(slaveTargetUrlListener2, 404);

        checkPUTStatusCode(requestUrlSlave, body, 200);
        checkGETStatusCodeWithAwait(requestUrlSlave, 200);
        checkGETBodyWithAwait(slaveTargetUrlListener1, body);
        checkGETBodyWithAwait(slaveTargetUrlListener2, body);
        checkDELETEStatusCode(requestUrlSlave, 200);
        checkGETStatusCodeWithAwait(requestUrlSlave, 404);
        checkGETStatusCodeWithAwait(slaveTargetUrlListener1, 404);
        checkGETStatusCodeWithAwait(slaveTargetUrlListener2, 404);

        TestUtils.unregisterListener(registerUrlListener1);
        TestUtils.unregisterListener(registerUrlListener2);

        /*
         * Sending request, slave listener hooked
         */
        TestUtils.registerListener(registerUrlListener2, targetListener2, methodsListener2);

        checkPUTStatusCode(requestUrlMaster, body, 200);
        checkGETStatusCodeWithAwait(requestUrlMaster, 200);
        checkGETStatusCodeWithAwait(masterTargetUrlListener1, 404);
        checkGETStatusCodeWithAwait(slaveTargetUrlListener2, 404);
        checkDELETEStatusCode(requestUrlMaster, 200);
        checkGETStatusCodeWithAwait(requestUrlMaster, 404);

        checkPUTStatusCode(requestUrlSlave, body, 200);
        checkGETStatusCodeWithAwait(requestUrlSlave, 200);
        checkGETStatusCodeWithAwait(slaveTargetUrlListener1, 404);
        checkGETBodyWithAwait(slaveTargetUrlListener2, body);
        checkDELETEStatusCode(requestUrlSlave, 200);
        checkGETStatusCodeWithAwait(requestUrlSlave, 404);
        checkGETStatusCodeWithAwait(slaveTargetUrlListener2, 404);

        TestUtils.unregisterListener(registerUrlListener2);

        async.complete();
    }

    @Test
    public void testUniqueName(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        String serviceName = "aQuickService";
        String serviceId = "1";

        String target = targetUrlBase + "/" + serviceName;

        String registerUrlListener1 = requestUrlBase + "/1stTest" + TestUtils.getHookListenersUrlSuffix() + serviceName + "/" + serviceId;
        String registerUrlListener2 = requestUrlBase + "/2ndTest" + TestUtils.getHookListenersUrlSuffix() + serviceName + "/" + serviceId;

        String body1 = "{\"foo\" : \"bar1\"}";
        String body2 = "{\"foo\" : \"bar2\"}";

        String put1 = requestUrlBase + "/1stTest/r1";
        String put2 = requestUrlBase + "/2ndTest/r2";

        String target1 = target + "/r1";
        String target2 = target + "/r2";

        // register both listener
        TestUtils.registerListener(registerUrlListener1, target, null, null);
        TestUtils.registerListener(registerUrlListener2, target, null, null);

        // Put first on one listener ...
        checkPUTStatusCode(put1, body1, 200);
        checkGETBodyWithAwait(put1, body1);
        checkGETBodyWithAwait(target1, body1);
        checkDELETEStatusCode(put1, 200);
        checkGETStatusCodeWithAwait(put1, 404);
        checkGETStatusCodeWithAwait(target1, 404);

        // .. then on the other
        checkPUTStatusCode(put2, body2, 200);
        checkGETBodyWithAwait(put2, body2);
        checkGETBodyWithAwait(target2, body2);
        checkDELETEStatusCode(put2, 200);
        checkGETStatusCodeWithAwait(put2, 404);
        checkGETStatusCodeWithAwait(target2, 404);

        // unregister everything
        TestUtils.unregisterListener(registerUrlListener1);
        TestUtils.unregisterListener(registerUrlListener2);

        async.complete();
    }

    @Test
    public void testHookFilter(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();
        String pattern = ROOT + "/.*/notfiltered/.*";

        String serviceName = "aPatternCheck";
        String serviceId = "1";
        String target = targetUrlBase + "/" + serviceName;

        String registerUrlListener = requestUrlBase + "/1stTest" + TestUtils.getHookListenersUrlSuffix() + serviceName + "/" + serviceId;

        String body = "{\"foo\" : \"bar1\"}";

        // should be filtered (means, should not be found by the test).
        String put1 = requestUrlBase + "/1stTest/123/filtered/v1/body";

        // should not be filtered (means, should be found by the test).
        String put2 = requestUrlBase + "/1stTest/456/notfiltered/v1/body";

        String target1 = target + "/123/filtered/v1/body";
        String target2 = target + "/456/notfiltered/v1/body";

        // register listener
        TestUtils.registerListener(registerUrlListener, target, null, pattern);

        // Put1
        checkPUTStatusCode(put1, body, 200);
        checkGETStatusCodeWithAwait(target1, 404);

        // Put2
        checkPUTStatusCode(put2, body, 200);
        checkGETStatusCodeWithAwait(target2, 200);
        checkGETBodyWithAwait(target2, body);

        TestUtils.unregisterListener(registerUrlListener);

        async.complete();
    }

    @Test
    public void testAfterTriggerTypeWithoutBefore(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        // Settings
        String subresource = "afterTwoListener";
        String listenerNo = "1";
        String listenerName = "firstListener";

        String registerUrlListener1 = requestUrlBase + "/" + subresource + TestUtils.getHookListenersUrlSuffix() + listenerName + "/" + listenerNo;
        String targetListener1 = targetUrlBase + "/" + listenerName;
        String[] methodsListener1 = new String[]{"GET", "PUT", "DELETE", "POST"};
        final String targetUrlListener1 = targetUrlBase + "/" + listenerName + "/" + "test";

        // -------

        final String requestUrl = requestUrlBase + "/" + subresource + "/" + "test";
        final String body = "{ \"name\" : \"" + subresource + "\"}";

        delete(requestUrl);
        delete(targetUrlListener1);

        /*
         * Sending request, both listener hooked
         */
        TestUtils.registerListener(registerUrlListener1, targetListener1, methodsListener1, null, null, null, HookTriggerType.AFTER, null);

        checkPUTStatusCode(requestUrl, body, 200);
        checkGETStatusCodeWithAwait(requestUrl, 200);
        checkGETBodyWithAwait(targetUrlListener1, body);

        checkDELETEStatusCode(requestUrl, 200);
        checkGETStatusCodeWithAwait(requestUrl, 404);
        checkGETStatusCodeWithAwait(targetUrlListener1, 404);

        TestUtils.unregisterListener(registerUrlListener1);

        async.complete();
    }

    @Test
    public void testAfterTriggerTypeWithRoute(TestContext context) {
        Async async = context.async();
        delete();
        JsonObject rules = new JsonObject();

        // Settings
        String subresource = "afterListener";
        String listenerName = "listenerName";
        String routeName = "routePathTest";
        String resourceBase = requestUrlBase + "/" + subresource;

        String registerUrlListener = resourceBase + TestUtils.getHookListenersUrlSuffix() + listenerName;
        String targetListener = targetUrlBase + "/" + listenerName;
        String[] methods = new String[]{"GET", "PUT", "DELETE", "POST"};
        final String hookAfterTarget = targetListener + "/" + "test";

        // -------
        final String requestUrl = resourceBase + TestUtils.getHookRouteUrlSuffix();
        final String body = "{ \"name\" : \"" + subresource + "\"}";
        final String target = SERVER_ROOT + "/tests/gateleen/targetresource/" + routeName;

        final String routedResource = resourceBase + "/test";
        final String routeTarget = targetUrlBase + "/" + routeName + "/test";

        JsonObject rule = TestUtils.createRoutingRule(ImmutableMap.of(
                "description",
                "Respond the request with status code 503",
                "path",
                SERVER_ROOT + "/return-with-status-code/503"));

        rules = TestUtils.addRoutingRuleMainStorage(rules);
        rules = TestUtils.addRoutingRule(rules, SERVER_ROOT + routedResource, rule);
        rules = TestUtils.addRoutingRuleHooks(rules);

        TestUtils.putRoutingRules(rules);

        delete(requestUrl);
        delete(hookAfterTarget);
        delete(routeTarget);

        /*
         * Sending request, listener hooked
         */
        TestUtils.registerListener(registerUrlListener, targetListener, methods, null, null, null, HookTriggerType.AFTER, null);

        checkPUTStatusCode(routedResource, body, 503);
        checkGETStatusCodeWithAwait(routedResource, 503);
        checkGETStatusCodeWithAwait(routeTarget, 404);
        checkGETStatusCodeWithAwait(hookAfterTarget, 404);

        // add a routing
        TestUtils.registerRoute(requestUrl, target, methods);

        checkPUTStatusCode(routedResource, body, 200);
        checkGETStatusCodeWithAwait(routedResource, 200);
        checkGETBodyWithAwait(routeTarget, body);
        checkGETBodyWithAwait(hookAfterTarget, body);

        checkDELETEStatusCode(routedResource, 200);
        checkGETStatusCodeWithAwait(routedResource, 404);
        checkGETStatusCodeWithAwait(routeTarget, 404);
        checkGETStatusCodeWithAwait(hookAfterTarget, 404);

        TestUtils.unregisterListener(registerUrlListener);

        async.complete();
    }

    @Test
    public void testAfterTriggerType(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        // Settings
        String subresource = "afterTwoListener";
        String listenerNo = "1";
        String listenerName = "firstListener";

        String registerUrlListener1 = requestUrlBase + "/" + subresource + TestUtils.getHookListenersUrlSuffix() + listenerName + "/" + listenerNo;
        String targetListener1 = targetUrlBase + "/" + listenerName;
        String[] methodsListener1 = new String[]{"GET", "PUT", "DELETE", "POST"};
        final String targetUrlListener1 = targetUrlBase + "/" + listenerName + "/" + "test";

        listenerNo = "2";
        listenerName = "secondListener";

        String registerUrlListener2 = requestUrlBase + "/" + subresource + TestUtils.getHookListenersUrlSuffix() + listenerName + "/" + listenerNo;
        String targetListener2 = targetUrlBase + "/" + listenerName;
        String[] methodsListener2 = new String[]{"GET", "PUT", "DELETE", "POST"};
        final String targetUrlListener2 = targetUrlBase + "/" + listenerName + "/" + "test";
        // -------

        final String requestUrl = requestUrlBase + "/" + subresource + "/" + "test";
        final String body = "{ \"name\" : \"" + subresource + "\"}";

        delete(requestUrl);
        delete(targetUrlListener1);
        delete(targetUrlListener2);

        /*
         * Sending request, both listener hooked
         */
        TestUtils.registerListener(registerUrlListener1, targetListener1, methodsListener1, null, null, null, HookTriggerType.AFTER, null);
        TestUtils.registerListener(registerUrlListener2, targetListener2, methodsListener2, null, null, null, HookTriggerType.BEFORE, null);

        checkPUTStatusCode(requestUrl, body, 200);
        checkGETStatusCodeWithAwait(requestUrl, 200);
        checkGETBodyWithAwait(targetUrlListener1, body);
        checkGETBodyWithAwait(targetUrlListener2, body);

        checkDELETEStatusCode(requestUrl, 200);
        checkGETStatusCodeWithAwait(requestUrl, 404);
        checkGETStatusCodeWithAwait(targetUrlListener1, 404);
        checkGETStatusCodeWithAwait(targetUrlListener2, 404);

        TestUtils.unregisterListener(registerUrlListener1);
        TestUtils.unregisterListener(registerUrlListener2);

        async.complete();
    }

    @Test
    public void testDeadLock(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        // url we want to check
        String registerUrlListener = requestUrlBase + "/deadLockTest" + TestUtils.getHookListenersUrlSuffix() + "deadlock" + "/" + 1;

        // url we expect something
        final String target = targetUrlBase + "/deadlock";

        // register listener
        TestUtils.registerListener(registerUrlListener, target, new String[]{"PUT"}, null);

        String url = requestUrlBase + "/deadLockTest/atest";
        String body = "{ \"name\" : \"test\" }";

        for (int index = 0; index < 100; index++) {
            delete(url);
            given().body(body).put(url);
            System.out.println(index);
            Awaitility.given().ignoreExceptions().await().atMost(FIVE_SECONDS).until(
                    () -> RestAssured.get(target + "/atest").then().extract().jsonPath().getString("name"), is("test"));
        }

        TestUtils.unregisterListener(registerUrlListener);

        async.complete();
    }

    @Test
    public void testHookQueueExpiryOverride(TestContext context) {
        // Prepare Environment
        // ----
        RestAssured.requestSpecification.basePath(SERVER_ROOT + "/");
        RestAssured.requestSpecification.urlEncodingEnabled(false);

        Async async = context.async();
        delete();
        initRoutingRules();
        // ----


        // Prepare Test
        // ----
        String sourceUrl = requestUrlBase + "/basecollection";
        String requestUrl = sourceUrl + TestUtils.getHookListenersUrlSuffix() + "testservice" + "/" + 1;
        String targetUrl = targetUrlBase + "/result";

        String queueName = "hook-queue-expiry-test";

        String putRequest = sourceUrl + "/test1";
        String putTarget = targetUrl + "/test1";
        String body = "{\"foo\" : \"bar1\"}";

        Map<String, String> headers = new HashMap<>();
        headers.put("x-queue", queueName);
        // ----

        // register Listener
        TestUtils.registerListener(requestUrl, targetUrl, null, null, 5,
                null, null, null, queueName);

        // lock queue
        String lockRequestUrl = "queuing/locks/" + queueName;
        given().put(lockRequestUrl);

        // put
        checkPUTStatusCode(putRequest, body, 200);

        // check if item is in queue
        when().get("queuing/queues/").then().assertThat().body("queues", hasItem(queueName));

        // the request should not be redirected (yet)
        when().get(putTarget).then().assertThat().statusCode(404);

        // wait till request is expired
        TestUtils.waitSomeTime(6);

        // unlock & flush
        when().delete(lockRequestUrl).then().assertThat().statusCode(200);
        given().headers(headers).put("test/gateleen/queueexpiry/flush");

        // wait some seconds
        TestUtils.waitSomeTime(2);

        // check if resource was written (should be discared)
        when().get(putTarget).then().assertThat().statusCode(404);

        // put
        checkPUTStatusCode(putRequest, body, 200);

        // get
        checkGETBodyWithAwait(putTarget, body);

        async.complete();
    }

    @Test
    public void testListenerWithStaticHeaders(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();
        RestAssured.requestSpecification.urlEncodingEnabled(false);


        // Settings
        String scenario = "testListenerWithStaticHeaders";
        String subresource = "lOne";
        String listenerNo = "1";
        String listenerName = "lOne";
        Map<String, String> staticHeaders = new LinkedHashMap<>();
        staticHeaders.put("x-test1", "test1");
        // -------

        String registerUrl = requestUrlBase + "/" + subresource + TestUtils.getHookListenersUrlSuffix() + listenerName + "/" + listenerNo;
        String target = "http://localhost:" + WIREMOCK_PORT + "/" + listenerName;
        String[] methods = new String[]{"GET", "PUT", "DELETE", "POST"};

        final String requestUrl = requestUrlBase + "/" + subresource + "/" + "test";
        final String targetUrl = target + "/" + "test";
        final String body = "{ \"name\" : \"" + subresource + "\"}";

        String targetUrlPart = "/" + listenerName + "/test";

        delete(requestUrl);
        delete(targetUrl);

        // register a listener
        TestUtils.registerListener(registerUrl, target, methods, null, null, staticHeaders);

        // prepare WireMock (stateful)
        WireMock.stubFor(WireMock.put(WireMock.urlEqualTo(targetUrlPart)).inScenario(scenario)
                .whenScenarioStateIs(STARTED)
                .withHeader("x-test1", WireMock.equalTo("test1"))
                .willReturn(aResponse().withStatus(200))
                .willSetStateTo("OK"));
        WireMock.stubFor(WireMock.get(WireMock.urlEqualTo(targetUrlPart)).inScenario(scenario)
                .whenScenarioStateIs("OK")
                .willReturn(aResponse().withStatus(200)));
        WireMock.stubFor(WireMock.get(WireMock.urlEqualTo(targetUrlPart)).inScenario(scenario)
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(404)));
        // ----

        // perform the put
        checkPUTStatusCode(requestUrl, body, 200);

        // check if the scenario state is OK
        checkGETStatusCodeWithAwait(targetUrl, 200);

        async.complete();
    }

    @Test
    public void testDetectLoopbacksInListener(TestContext context) {
        // Settings
        String subresource = "illegalLoopbackListener";
        String listenerNo = "1";
        String listenerName = "loopbackListener";
        // -------

        String registerUrl = requestUrlBase + "/" + subresource + TestUtils.getHookListenersUrlSuffix() + listenerName + "/" + listenerNo;
        String target = SERVER_ROOT + requestUrlBase + "/" + subresource + "/anySubResource";

        // register a listener - expect not accepted by Gateleen (expect http 400)
        String body = "{ \"destination\":\"" + target + "\"}";
        with().body(body).put(registerUrl).then().assertThat().statusCode(400);

        context.async().complete();
    }

    /**
     * Test for two listener monitoring the same resource including a headersFilter. <br />
     * eg. register / unregister: http://localhost:7012/gateleen/server/listenertest/fwTwoListener/_hooks/listeners/firstListener/1 <br />
     * register / unregister: http://localhost:7012/gateleen/server/listenertest/fwTwoListener/_hooks/listeners/secondListener/2 <br />
     * requestUrl: http://localhost:7012/gateleen/server/listenertest/fwTwoListener/test.
     */
    @Test
    public void testRequestForwardingForTwoListenerAtSameResourceButDifferentHeadersFilter(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        // Settings
        String subresource = "fwTwoListener";
        String listenerNo = "1";
        String listenerName = "firstListener";

        String registerUrlListener1 = requestUrlBase + "/" + subresource + TestUtils.getHookListenersUrlSuffix() + listenerName + "/" + listenerNo;
        String targetListener1 = targetUrlBase + "/" + listenerName;
        String[] methodsListener1 = new String[]{"PUT", "DELETE", "POST"};
        final String targetUrlListener1 = targetUrlBase + "/" + listenerName + "/" + "test";

        listenerNo = "2";
        listenerName = "secondListener";

        String registerUrlListener2 = requestUrlBase + "/" + subresource + TestUtils.getHookListenersUrlSuffix() + listenerName + "/" + listenerNo;
        String targetListener2 = targetUrlBase + "/" + listenerName;
        String[] methodsListener2 = new String[]{"PUT", "DELETE", "POST"};
        final String targetUrlListener2 = targetUrlBase + "/" + listenerName + "/" + "test";
        // -------

        final String requestUrl = requestUrlBase + "/" + subresource + "/" + "test";
        final String body = "{ \"name\" : \"" + subresource + "\"}";

        delete(requestUrl);
        delete(targetUrlListener1);
        delete(targetUrlListener2);

        /*
         * Sending request, listener not yet hooked
         */
        checkPUTStatusCode(requestUrl, body, 200);
        checkGETStatusCodeWithAwait(requestUrl, 200);
        checkGETStatusCodeWithAwait(targetUrlListener1, 404);
        checkGETStatusCodeWithAwait(targetUrlListener2, 404);
        checkDELETEStatusCode(requestUrl, 200);
        checkGETStatusCodeWithAwait(requestUrl, 404);

        /*
         * Sending request, one listener hooked
         */
        TestUtils.registerListener(registerUrlListener1, targetListener1, methodsListener1, null, null,
                null, null, "x-foo: (A|B)");
        TestUtils.registerListener(registerUrlListener2, targetListener2, methodsListener2, null, null,
                null, null, "x-foo: (C|D)");

        checkPUTStatusCode(requestUrl, body, 200, Headers.headers(new Header("x-foo", "A")));
        checkGETStatusCodeWithAwait(requestUrl, 200);
        checkGETBodyWithAwait(targetUrlListener1, body);
        checkGETStatusCodeWithAwait(targetUrlListener2, 404);
        checkDELETEStatusCode(requestUrl, 200, Headers.headers(new Header("x-foo", "A")));
        checkGETStatusCodeWithAwait(requestUrl, 404);
        checkGETStatusCodeWithAwait(targetUrlListener1, 404);

        TestUtils.unregisterListener(registerUrlListener1);
        TestUtils.unregisterListener(registerUrlListener2);

        async.complete();
    }

    /**
     * Checks if the DELETE request gets a response
     * with the given status code.
     *
     * @param requestUrl
     * @param statusCode
     */
    private void checkDELETEStatusCode(String requestUrl, int statusCode) {
        delete(requestUrl).then().assertThat().statusCode(statusCode);
    }

    /**
     * Checks if the DELETE request gets a response
     * with the given status code.
     *
     * @param requestUrl
     * @param statusCode
     * @param headers
     */
    private void checkDELETEStatusCode(String requestUrl, int statusCode, Headers headers) {
        with().headers(headers).delete(requestUrl).then().assertThat().statusCode(statusCode);
    }

    /**
     * Checks if the PUT request gets a response
     * with the given status code.
     *
     * @param requestUrl
     * @param body
     * @param statusCode
     */
    private void checkPUTStatusCode(String requestUrl, String body, int statusCode) {
        with().body(body).put(requestUrl).then().assertThat().statusCode(statusCode);
    }

    /**
     * Checks if the PUT request gets a response
     * with the given status code.
     *
     * @param requestUrl
     * @param body
     * @param statusCode
     * @param headers
     */
    private void checkPUTStatusCode(String requestUrl, String body, int statusCode, Headers headers) {
        with().headers(headers).body(body).put(requestUrl).then().assertThat().statusCode(statusCode);
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
        await().atMost(FIVE_SECONDS).until(() -> String.valueOf(when().get(request).getStatusCode()), equalTo(String.valueOf(statusCode)));
    }

    /**
     * Checks if the GET request of the
     * given resource returns the wished body.
     *
     * @param requestUrl
     * @param body
     */
    private void checkGETBodyWithAwait(final String requestUrl, final String body) {
        await().atMost(TEN_SECONDS).until(() -> when().get(requestUrl).then().extract().body().asString(), equalTo(body));
    }
}

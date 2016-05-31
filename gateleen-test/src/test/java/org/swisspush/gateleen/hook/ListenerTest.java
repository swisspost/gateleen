package org.swisspush.gateleen.hook;

import com.jayway.awaitility.Awaitility;
import com.jayway.awaitility.Duration;
import com.jayway.restassured.RestAssured;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;

import java.util.HashMap;
import java.util.Map;

import static com.jayway.awaitility.Awaitility.await;
import static com.jayway.restassured.RestAssured.*;
import static org.hamcrest.CoreMatchers.*;

/**
 * Test class for the hook listener feature.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
@RunWith(VertxUnitRunner.class)
public class ListenerTest extends AbstractTest {
    private String requestUrlBase;
    private String targetUrlBase;

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
        String[] methods = new String[] { "GET", "PUT", "DELETE", "POST" };

        registerListener(requestUrl, target, methods, null);
        unregisterListener(requestUrl);

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
        String[] methods = new String[] { "GET", "PUT", "DELETE", "POST" };

        final String requestUrl = requestUrlBase + "/" + subresource + "/" + "test";
        final String targetUrl = target + "/" + "test";
        final String body = "{ \"name\" : \"" + subresource + "\"}";

        delete(requestUrl);
        delete(targetUrl);

        // register a listener
        registerListener(registerUrl, target, methods, 4);

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

        unregisterListener(registerUrl);

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
        String[] methodsListener1 = new String[] { "GET", "PUT", "DELETE", "POST" };
        final String targetUrlListener1 = targetUrlBase + "/" + listenerName + "/" + "test";

        listenerNo = "2";
        listenerName = "secondListener";

        String registerUrlListener2 = requestUrlBase + "/" + subresource + TestUtils.getHookListenersUrlSuffix() + listenerName + "/" + listenerNo;
        String targetListener2 = targetUrlBase + "/" + listenerName;
        String[] methodsListener2 = new String[] { "GET", "PUT", "DELETE", "POST" };
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
        registerListener(registerUrlListener1, targetListener1, methodsListener1, 4);

        checkPUTStatusCode(requestUrl, body, 200);
        checkGETStatusCodeWithAwait(requestUrl, 200);
        checkGETBodyWithAwait(targetUrlListener1, body);
        checkGETStatusCodeWithAwait(targetUrlListener2, 404);
        checkDELETEStatusCode(requestUrl, 200);
        checkGETStatusCodeWithAwait(requestUrl, 404);
        checkGETStatusCodeWithAwait(targetUrlListener1, 404);

        unregisterListener(registerUrlListener1);

        /*
         * Sending request, both listener hooked
         */
        registerListener(registerUrlListener1, targetListener1, methodsListener1, 4);
        registerListener(registerUrlListener2, targetListener2, methodsListener2, 4);

        checkPUTStatusCode(requestUrl, body, 200);
        checkGETStatusCodeWithAwait(requestUrl, 200);
        checkGETBodyWithAwait(targetUrlListener1, body);
        checkGETBodyWithAwait(targetUrlListener2, body);
        checkDELETEStatusCode(requestUrl, 200);
        checkGETStatusCodeWithAwait(requestUrl, 404);
        checkGETStatusCodeWithAwait(targetUrlListener1, 404);
        checkGETStatusCodeWithAwait(targetUrlListener2, 404);

        unregisterListener(registerUrlListener1);
        unregisterListener(registerUrlListener2);

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
        String[] methodsListener1 = new String[] { "GET", "PUT", "DELETE", "POST" };
        final String masterTargetUrlListener1 = targetUrlBase + "/" + listenerName + "/" + "test";
        final String slaveTargetUrlListener1 = targetUrlBase + "/" + listenerName + "/" + additionalSubResource + "/" + "test";

        listenerNo = "2";
        listenerName = "secondDiffListener";

        String registerUrlListener2 = requestUrlBase + "/" + subresource + "/" + additionalSubResource + TestUtils.getHookListenersUrlSuffix() + listenerName + "/" + listenerNo;
        String targetListener2 = targetUrlBase + "/" + listenerName;
        String[] methodsListener2 = new String[] { "GET", "PUT", "DELETE", "POST" };
        final String slaveTargetUrlListener2 = targetUrlBase + "/" + listenerName + "/" + "test";
        // -------

        // just for security reasons
        unregisterListener(registerUrlListener1);
        unregisterListener(registerUrlListener2);

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
        registerListener(registerUrlListener1, targetListener1, methodsListener1, 4);

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

        unregisterListener(registerUrlListener1);

        /*
         * Sending request, both listener hooked
         */
        registerListener(registerUrlListener1, targetListener1, methodsListener1, 4);
        registerListener(registerUrlListener2, targetListener2, methodsListener2, 4);

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

        unregisterListener(registerUrlListener1);
        unregisterListener(registerUrlListener2);

        /*
         * Sending request, slave listener hooked
         */
        registerListener(registerUrlListener2, targetListener2, methodsListener2, 4);

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

        unregisterListener(registerUrlListener2);

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
        registerListener(registerUrlListener1, target, null, null);
        registerListener(registerUrlListener2, target, null, null);

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
        unregisterListener(registerUrlListener1);
        unregisterListener(registerUrlListener2);

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
        registerListener(registerUrlListener, target, null, null, pattern);

        // Put1
        checkPUTStatusCode(put1, body, 200);
        checkGETStatusCodeWithAwait(target1, 404);

        // Put2
        checkPUTStatusCode(put2, body, 200);
        checkGETStatusCodeWithAwait(target2, 200);
        checkGETBodyWithAwait(target2, body);

        unregisterListener(registerUrlListener);

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
        registerListener(registerUrlListener, target, null, null);

        String url = requestUrlBase + "/deadLockTest/atest";
        String body = "{ \"name\" : \"test\" }";

        for (int index = 0; index < 100; index++) {
            delete(url);
            given().body(body).put(url);
            System.out.println(index);
            Awaitility.given().ignoreExceptions().await().atMost(Duration.FIVE_SECONDS).until(
                    () -> RestAssured.get(target + "/atest").then().extract().jsonPath().getString("name"), is("test"));
        }

        unregisterListener(registerUrlListener);

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

        String queueName = HookHandler.LISTENER_QUEUE_PREFIX + "-" + hookHandler.getUniqueListenerId(SERVER_ROOT + requestUrl);

        String putRequest = sourceUrl + "/test1";
        String putTarget = targetUrl + "/test1";
        String body = "{\"foo\" : \"bar1\"}";

        Map<String, String> headers = new HashMap<>();
        headers.put("x-queue", queueName);
        // ----

        // register Listener
        registerListener(requestUrl,targetUrl,null, 10, null, 5);

        // lock queue
        String lockRequestUrl = "queuing/locks/" + queueName;
        given().put(lockRequestUrl);

        // put
        checkPUTStatusCode(putRequest,body, 200);

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
        checkPUTStatusCode(putRequest,body, 200);

        // get
        checkGETBodyWithAwait(putTarget, body);

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
     * Checks if the GET request for the
     * resource gets a response with
     * the given status code.
     * 
     * @param request
     * @param statusCode
     */
    private void checkGETStatusCodeWithAwait(final String request, final Integer statusCode) {
        await().atMost(Duration.FIVE_SECONDS).until(() -> String.valueOf(when().get(request).getStatusCode()), equalTo(String.valueOf(statusCode)));
    }

    /**
     * Checks if the GET request of the
     * given resource returns the wished body.
     * 
     * @param requestUrl
     * @param body
     */
    private void checkGETBodyWithAwait(final String requestUrl, final String body) {
        await().atMost(Duration.TEN_SECONDS).until(() -> when().get(requestUrl).then().extract().body().asString(), equalTo(body));
    }

    /**
     * Registers a listener.
     *
     * @param requestUrl
     * @param target
     * @param methods
     * @param expireTime
     */
    private void registerListener(final String requestUrl, final String target, String[] methods, Integer expireTime) {
        registerListener(requestUrl, target, methods, expireTime, null);
    }

    /**
     * Registers a listener.
     *
     * @param requestUrl
     * @param target
     * @param methods
     * @param expireTime
     * @param filter
     */
    private void registerListener(final String requestUrl, final String target, String[] methods, Integer expireTime, String filter) {
        registerListener(requestUrl, target, methods, expireTime, filter, null);
    }

    /**
     * Registers a listener with a filter.
     *
     * @param requestUrl
     * @param target
     * @param methods
     * @param expireTime
     * @param filter
     * @param queueExpireTime
     */
    private void registerListener(final String requestUrl, final String target, String[] methods, Integer expireTime, String filter, Integer queueExpireTime) {
        String body = "{ \"destination\":\"" + target + "\"";

        String m = null;
        if (methods != null) {
            for (String method : methods) {
                m += "\"" + method + "\", ";
            }
            m = m.endsWith(", ") ? m.substring(0, m.lastIndexOf(",")) : m;
            m = "\"methods\": [" + m + "]";
        }
        body += expireTime != null ? ", \""+ HookHandler.EXPIRE_AFTER + "\" : " + expireTime : "";
        body += queueExpireTime != null ? ", \""+ HookHandler.QUEUE_EXPIRE_AFTER + "\" : " + queueExpireTime : "";
        body += filter != null ? ", \"filter\" : \"" + filter + "\"" : "";
        body = body + "}";

        with().body(body).put(requestUrl).then().assertThat().statusCode(200);
    }

    /**
     * Unregisters a listener.
     *
     * @param request
     */
    private void unregisterListener(String request) {
        delete(request).then().assertThat().statusCode(200);
    }
}

package org.swisspush.gateleen.delegate;

import com.jayway.restassured.RestAssured;
import com.jayway.restassured.response.Response;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.jayway.restassured.RestAssured.*;

/**
 * Testclass for the DelegateHandler and it features.
 *
 * @author https://github.com/ljucam [Mario Ljuca]
 */
@RunWith(VertxUnitRunner.class)
public class DelegateTest extends AbstractTest {
    private static final String DELEGATE_BASE = "/delegate/v1/delegates";

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath(SERVER_ROOT);
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


    @Test
    public void testDelegateExecution_OneRequest(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();
        prepareTestEnvironment();

        String delegate1 = DELEGATE_BASE + "/myFirstDelegate/definition";
        String delegateExec1 = DELEGATE_BASE + "/myFirstDelegate/execution";

        // prepare the delegate
        // -----
        JsonArray requests = new JsonArray();
        JsonObject payload = new JsonObject();
        payload.put("source", "/playground/server/items/$1?expand=100&zip=true");
        payload.put("destination", "/playground/server/zips/items/$1.zip");
        JsonObject request = createRequest(new HashMap<>(), SERVER_ROOT + "/v1/copy", HttpMethod.POST, payload);
        requests.add(request);

        List<HttpMethod> methods = new ArrayList<>();
        methods.add(HttpMethod.PUT);

        JsonObject delegate = createDelegate(methods, ".*/([^/]+.*)", requests);
        System.out.println(delegate.toString());
        // -----


        // Registration
        given().body(delegate.toString()).put(delegate1).then().assertThat().statusCode(200);
        get(delegate1).then().assertThat().statusCode(200);

        TestUtils.waitSomeTime(1);

        // Execution
        given().put(delegateExec1 + "/user2").then().assertThat().statusCode(200);
        TestUtils.checkGETStatusCodeWithAwait("zips/items/user2.zip", 200);


        // Check if result is valid
        Response response = get("zips/items/user2.zip/server/items/user2/res1");
        context.assertEquals(200, response.getStatusCode());
        context.assertEquals(get("items/user2/res1").body().asString(), response.getBody().asString());
        response = get("zips/items/user2.zip/server/items/user2/res2");
        context.assertEquals(200, response.getStatusCode());
        context.assertEquals(get("items/user2/res2").body().asString(), response.getBody().asString());


        // Unregistration
        delete(delegate1).then().assertThat().statusCode(200);
        TestUtils.checkGETStatusCodeWithAwait(delegate1, 404);

        async.complete();
    }

    @Test
    public void testDelegateExecution_MultipleRequest_WithQueue(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();
        prepareTestEnvironment();

        String delegate1 = DELEGATE_BASE + "/mySeventhDelegate/definition";
        String delegateExec1 = DELEGATE_BASE + "/mySeventhDelegate/execution";

        // prepare the delegate
        // -----
        JsonArray requests = new JsonArray();
        JsonObject payload1 = new JsonObject();
        Map<String, Object> headers = new HashMap<>();
        headers.put("x-queue", "multiple");

        payload1.put("source", "/playground/server/items/$1?expand=100&zip=true");
        payload1.put("destination", "/playground/server/zips/$1.zip");
        JsonObject request1 = createRequest(headers, SERVER_ROOT + "/v1/copy", HttpMethod.POST, payload1);

        JsonObject payload2 = new JsonObject();
        payload2.put("source", "/playground/server/zips/$1.zip");
        payload2.put("destination", "/playground/server/copytest/myCopy.zip");
        JsonObject request2 = createRequest(headers, SERVER_ROOT + "/v1/copy", HttpMethod.POST, payload2);

        requests.add(request1);
        requests.add(request2);

        List<HttpMethod> methods = new ArrayList<>();
        methods.add(HttpMethod.PUT);

        JsonObject delegate = createDelegate(methods, ".*/([^/]+.*)", requests);
        System.out.println(delegate.toString());
        // -----


        // Registration
        given().body(delegate.toString()).put(delegate1).then().assertThat().statusCode(200);
        get(delegate1).then().assertThat().statusCode(200);

        TestUtils.waitSomeTime(1);

        // Execution
        given().put(delegateExec1 + "/user1").then().assertThat().statusCode(202);
        TestUtils.checkGETStatusCodeWithAwait("zips/user1.zip", 200);
        TestUtils.checkGETStatusCodeWithAwait("copytest/myCopy.zip", 200);


        // Unregistration
        delete(delegate1).then().assertThat().statusCode(200);
        TestUtils.checkGETStatusCodeWithAwait(delegate1, 404);

        async.complete();
    }

    @Test
    public void testDelegateExecution_MultipleRequest(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();
        prepareTestEnvironment();

        String delegate1 = DELEGATE_BASE + "/mySecondDelegate/definition";
        String delegateExec1 = DELEGATE_BASE + "/mySecondDelegate/execution";

        // prepare the delegate
        // -----
        JsonArray requests = new JsonArray();
        JsonObject payload1 = new JsonObject();
        payload1.put("source", "/playground/server/items/$1?expand=100&zip=true");
        payload1.put("destination", "/playground/server/zips/items/$1.zip");
        JsonObject request1 = createRequest(new HashMap<>(), SERVER_ROOT + "/v1/copy", HttpMethod.POST, payload1);

        JsonObject payload2 = new JsonObject();
        payload2.put("source", "/playground/server/items/$1?expand=100&zip=true");
        payload2.put("destination", "/playground/server/copytest/myCopy.zip");
        JsonObject request2 = createRequest(new HashMap<>(), SERVER_ROOT + "/v1/copy", HttpMethod.POST, payload2);

        requests.add(request1);
        requests.add(request2);

        List<HttpMethod> methods = new ArrayList<>();
        methods.add(HttpMethod.PUT);

        JsonObject delegate = createDelegate(methods, ".*/([^/]+.*)", requests);
        System.out.println(delegate.toString());
        // -----


        // Registration
        given().body(delegate.toString()).put(delegate1).then().assertThat().statusCode(200);
        get(delegate1).then().assertThat().statusCode(200);

        TestUtils.waitSomeTime(1);

        // Execution
        given().put(delegateExec1 + "/user1").then().assertThat().statusCode(200);
        TestUtils.checkGETStatusCodeWithAwait("zips/items/user1.zip", 200);
        TestUtils.checkGETStatusCodeWithAwait("copytest/myCopy.zip", 200);


        // Unregistration
        delete(delegate1).then().assertThat().statusCode(200);
        TestUtils.checkGETStatusCodeWithAwait(delegate1, 404);

        async.complete();
    }

    @Test
    public void testDelegateExecution_MultipleMatchingGroups(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();
        prepareTestEnvironment();

        String delegate1 = DELEGATE_BASE + "/myThirdDelegate/definition";
        String delegateExec1 = DELEGATE_BASE + "/myThirdDelegate/execution";

        // prepare the delegate
        // -----
        JsonArray requests = new JsonArray();
        JsonObject payload = new JsonObject();
        payload.put("source", "/playground/server/items/user1/$2");
        payload.put("destination", "/playground/server/patterntest/$2/$1");
        JsonObject request = createRequest(new HashMap<>(), SERVER_ROOT + "/v1/copy", HttpMethod.POST, payload);
        requests.add(request);

        List<HttpMethod> methods = new ArrayList<>();
        methods.add(HttpMethod.PUT);

        JsonObject delegate = createDelegate(methods, ".*/execution/([^/?]+)/([^/?]+)", requests);
        System.out.println(delegate.toString());
        // -----


        // Registration
        given().body(delegate.toString()).put(delegate1).then().assertThat().statusCode(200);
        get(delegate1).then().assertThat().statusCode(200);

        TestUtils.waitSomeTime(1);

        // Execution
        given().put(delegateExec1 + "/result/res1").then().assertThat().statusCode(200);
        TestUtils.checkGETStatusCodeWithAwait("patterntest/res1/result", 200);


        // Check if result is valid
        context.assertEquals(get("items/user1/res1").body().asString(), get("patterntest/res1/result").body().asString());


        // Unregistration
        delete(delegate1).then().assertThat().statusCode(200);
        TestUtils.checkGETStatusCodeWithAwait(delegate1, 404);

        async.complete();
    }

    @Test
    public void testDelegateExecution_NoMatchingDelegate(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();
        prepareTestEnvironment();
        String delegate1 = DELEGATE_BASE + "/myFourthDelegate/definition";
        String wrongDelegate = DELEGATE_BASE + "/blah/execution";

        // prepare the delegate
        // -----
        JsonArray requests = new JsonArray();
        JsonObject payload = new JsonObject();
        payload.put("source", "/playground/server/items/$1?expand=100&zip=true");
        payload.put("destination", "/playground/server/zips/items/$1.zip");
        JsonObject request = createRequest(new HashMap<>(), SERVER_ROOT + "/v1/copy", HttpMethod.POST, payload);
        requests.add(request);

        List<HttpMethod> methods = new ArrayList<>();
        methods.add(HttpMethod.PUT);

        JsonObject delegate = createDelegate(methods, ".*/([^/]+.*)", requests);
        System.out.println(delegate.toString());
        // -----


        // Registration
        given().body(delegate.toString()).put(delegate1).then().assertThat().statusCode(200);
        get(delegate1).then().assertThat().statusCode(200);

        TestUtils.waitSomeTime(1);

        // Execution
        given().put(wrongDelegate + "/user2").then().assertThat().statusCode(200);
        TestUtils.checkGETStatusCodeWithAwait("zips/items/user2.zip", 404);

        // Unregistration
        delete(delegate1).then().assertThat().statusCode(200);
        TestUtils.checkGETStatusCodeWithAwait(delegate1, 404);

        async.complete();
    }

    @Test
    public void testDelegateUnregistration_NoMatchingDelegate(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();
        prepareTestEnvironment();
        String delegate1 = DELEGATE_BASE + "/myFifthDelegate/definition";
        String unregDelegate = DELEGATE_BASE + "/blah/definition";

        // prepare the delegate
        // -----
        JsonArray requests = new JsonArray();
        JsonObject payload = new JsonObject();
        payload.put("source", "/playground/server/items/$1?expand=100&zip=true");
        payload.put("destination", "/playground/server/zips/items/$1.zip");
        JsonObject request = createRequest(new HashMap<>(), SERVER_ROOT + "/v1/copy", HttpMethod.POST, payload);
        requests.add(request);

        List<HttpMethod> methods = new ArrayList<>();
        methods.add(HttpMethod.PUT);

        JsonObject delegate = createDelegate(methods, ".*/([^/]+.*)", requests);
        System.out.println(delegate.toString());
        // -----


        // Registration
        given().body(delegate.toString()).put(delegate1).then().assertThat().statusCode(200);
        get(delegate1).then().assertThat().statusCode(200);

        TestUtils.waitSomeTime(1);

        // Unregistration
        delete(unregDelegate).then().assertThat().statusCode(404);

        async.complete();
    }

    @Test
    public void testDelegateRegistration_MalformedDefinition(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();
        prepareTestEnvironment();
        String delegate1 = DELEGATE_BASE + "/mySixthDelegate/definition";

        // Registration
        given().body("{ \"methods\" : \"GET\" }").put(delegate1).then().assertThat().statusCode(400);


        async.complete();
    }

    /**
     * Creates some resources
     */
    private void prepareTestEnvironment() {
        given().body("{\"id\" : \"u1r1\"}").put("items/user1/res1");
        given().body("{\"id\" : \"u1r2\"}").put("items/user1/res2");
        given().body("{\"id\" : \"u2r1\"}").put("items/user2/res1");
        given().body("{\"id\" : \"u2r2\"}").put("items/user2/res2");
    }

    /**
     * Creates a new request with the given parameters.
     *
     * @param headers
     * @param uri
     * @param method
     * @param payload
     * @return
     */
    private JsonObject createRequest(Map<String, Object> headers, String uri, HttpMethod method, JsonObject payload) {
        JsonObject request = new JsonObject();
        JsonArray jsonHeaders = new JsonArray();
        headers.forEach((s, o) -> {
            JsonArray kv = new JsonArray();
            kv.add(s);
            kv.add(o);
            jsonHeaders.add(kv);
        });
        request.put("headers", jsonHeaders);
        request.put("uri", uri);
        request.put("method", method.toString());
        request.put("payload", payload);
        return request;
    }

    /**
     * Creates a new delegate with the given parameters.
     *
     * @param methods
     * @param pattern
     * @param requests
     * @return
     */
    private JsonObject createDelegate(List<HttpMethod> methods, String pattern, JsonArray requests) {
        JsonObject body = new JsonObject();
        JsonArray jsonMethods = new JsonArray();
        methods.forEach(method -> jsonMethods.add(method.toString()));
        body.put("methods", methods);
        body.put("pattern", pattern);
        body.put("requests", requests);
        return body;
    }
}

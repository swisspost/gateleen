package org.swisspush.gateleen.delegate;

import io.restassured.RestAssured;
import io.restassured.response.Response;
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

import static io.restassured.RestAssured.*;

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
     * Define one request with a valid payload transformation spec. Requires the delegate execution request to have
     * a valid json payload. Output payload must be input payload transformed according to transformation spec.
     *
     * @param context
     */
    @Test
    public void testDelegateExecution_OneRequestTransformation(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        String delegate1 = DELEGATE_BASE + "/mySeventhDelegate/definition";
        String delegateExec1 = DELEGATE_BASE + "/mySeventhDelegate/execution";

        // prepare the delegate
        // -----
        JsonArray requests = new JsonArray();

        String transformation = "[\n" +
                "  {\n" +
                "    \"operation\": \"shift\",\n" +
                "    \"spec\": {\n" +
                "      \"@\": \"records[0].value\"\n" +
                "    }\n" +
                "  }\n" +
                "]";

        JsonArray transformationSpec = new JsonArray(transformation);
        long uniqueID = System.currentTimeMillis();
        JsonObject request = createRequest(new HashMap<>(), SERVER_ROOT + "/tests/delegate/" + uniqueID, HttpMethod.PUT, transformationSpec);
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
        given().body("{ \"foo\": \"bar\" }").put(delegateExec1 + "/someRes").then().assertThat().statusCode(200);
        TestUtils.checkGETStatusCodeWithAwait("/tests/delegate/" + uniqueID, 200);

        // Check if result is valid
        Response response = get("/tests/delegate/" + uniqueID);
        JsonObject transformedActual = new JsonObject(response.getBody().asString());

        String transformedBody = "{\n" +
                "  \"records\" : [ {\n" +
                "    \"value\" : {\n" +
                "      \"foo\" : \"bar\"\n" +
                "    }\n" +
                "  } ]\n" +
                "}";
        JsonObject transformedExpected = new JsonObject(transformedBody);

        context.assertEquals(200, response.getStatusCode());
        context.assertEquals(transformedExpected, transformedActual);

        // Unregistration
        delete(delegate1).then().assertThat().statusCode(200);
        TestUtils.checkGETStatusCodeWithAwait(delegate1, 404);

        async.complete();
    }

    /**
     * Define multiple requests with (different) valid payload transformation specs. Requires the delegate execution request to have
     * a valid json payload. Output payload must be input payload transformed according to the corresponding transformation spec.
     *
     * @param context
     */
    @Test
    public void testDelegateExecution_MultipleRequestsTransformation(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        String delegate1 = DELEGATE_BASE + "/mySeventhDelegate/definition";
        String delegateExec1 = DELEGATE_BASE + "/mySeventhDelegate/execution";

        // prepare the delegate
        // -----
        JsonArray requests = new JsonArray();

        String transformation = "[\n" +
                "  {\n" +
                "    \"operation\": \"shift\",\n" +
                "    \"spec\": {\n" +
                "      \"@\": \"records[0].value\"\n" +
                "    }\n" +
                "  }\n" +
                "]";

        String transformation2 = "[\n" +
                "  {\n" +
                "    \"operation\": \"shift\",\n" +
                "    \"spec\": {\n" +
                "      \"@\": \"items[0].item\"\n" +
                "    }\n" +
                "  }\n" +
                "]";


        long uniqueID = System.currentTimeMillis();
        JsonObject request = createRequest(new HashMap<>(), SERVER_ROOT + "/tests/delegate/" + uniqueID, HttpMethod.PUT, new JsonArray(transformation));
        JsonObject request2 = createRequest(new HashMap<>(), SERVER_ROOT + "/tests/delegate/res2/" + uniqueID, HttpMethod.PUT, new JsonArray(transformation2));

        requests.add(request);
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
        given().body("{ \"foo\": \"bar\" }").put(delegateExec1 + "/someRes").then().assertThat().statusCode(200);
        TestUtils.checkGETStatusCodeWithAwait("/tests/delegate/" + uniqueID, 200);
        TestUtils.checkGETStatusCodeWithAwait("/tests/delegate/res2/" + uniqueID, 200);

        // Check if results are valid
        Response response = get("/tests/delegate/" + uniqueID);
        JsonObject transformedActual = new JsonObject(response.getBody().asString());

        String transformedBody = "{\n" +
                "  \"records\" : [ {\n" +
                "    \"value\" : {\n" +
                "      \"foo\" : \"bar\"\n" +
                "    }\n" +
                "  } ]\n" +
                "}";
        JsonObject transformedExpected = new JsonObject(transformedBody);

        context.assertEquals(200, response.getStatusCode());
        context.assertEquals(transformedExpected, transformedActual);

        Response response2 = get("/tests/delegate/res2/" + uniqueID);
        JsonObject transformedActual2 = new JsonObject(response2.getBody().asString());

        String transformedBody2 = "{\n" +
                "  \"items\" : [ {\n" +
                "    \"item\" : {\n" +
                "      \"foo\" : \"bar\"\n" +
                "    }\n" +
                "  } ]\n" +
                "}";
        JsonObject transformedExpected2 = new JsonObject(transformedBody2);

        context.assertEquals(200, response2.getStatusCode());
        context.assertEquals(transformedExpected2, transformedActual2);

        // Unregistration
        delete(delegate1).then().assertThat().statusCode(200);
        TestUtils.checkGETStatusCodeWithAwait(delegate1, 404);

        async.complete();
    }

    /**
     * Define multiple requests with payload transformation and "payload" property. Requires the delegate execution request to have
     * a valid json payload. Output payload must be input payload transformed according to the transformation spec and payload defined
     * in delegate definition.
     *
     * @param context
     */
    @Test
    public void testDelegateExecution_MultipleRequestsMixedTransformationAndPayload(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        String delegate1 = DELEGATE_BASE + "/mySeventhDelegate/definition";
        String delegateExec1 = DELEGATE_BASE + "/mySeventhDelegate/execution";

        // prepare the delegate
        // -----
        JsonArray requests = new JsonArray();

        String transformation = "[\n" +
                "  {\n" +
                "    \"operation\": \"shift\",\n" +
                "    \"spec\": {\n" +
                "      \"@\": \"records[0].value\"\n" +
                "    }\n" +
                "  }\n" +
                "]";

        long uniqueID = System.currentTimeMillis();
        JsonObject request = createRequest(new HashMap<>(), SERVER_ROOT + "/tests/delegate/" + uniqueID, HttpMethod.PUT, new JsonArray(transformation));
        JsonObject request2 = createRequest(new HashMap<>(), SERVER_ROOT + "/tests/delegate/res2/" + uniqueID, HttpMethod.PUT, new JsonObject("{\"key\": \"value\"}"));

        requests.add(request);
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
        given().body("{ \"foo\": \"bar\" }").put(delegateExec1 + "/someRes").then().assertThat().statusCode(200);
        TestUtils.checkGETStatusCodeWithAwait("/tests/delegate/" + uniqueID, 200);
        TestUtils.checkGETStatusCodeWithAwait("/tests/delegate/res2/" + uniqueID, 200);

        // Check if results are valid
        Response response = get("/tests/delegate/" + uniqueID);
        JsonObject transformedActual = new JsonObject(response.getBody().asString());

        String transformedBody = "{\n" +
                "  \"records\" : [ {\n" +
                "    \"value\" : {\n" +
                "      \"foo\" : \"bar\"\n" +
                "    }\n" +
                "  } ]\n" +
                "}";
        JsonObject transformedExpected = new JsonObject(transformedBody);

        context.assertEquals(200, response.getStatusCode());
        context.assertEquals(transformedExpected, transformedActual);

        Response response2 = get("/tests/delegate/res2/" + uniqueID);
        JsonObject bodyActual = new JsonObject(response2.getBody().asString());

        JsonObject expected2 = new JsonObject("{\"key\": \"value\"}");

        context.assertEquals(200, response2.getStatusCode());
        context.assertEquals(expected2, bodyActual);

        // Unregistration
        delete(delegate1).then().assertThat().statusCode(200);
        TestUtils.checkGETStatusCodeWithAwait(delegate1, 404);

        async.complete();
    }

    /**
     * Invalid transformation spec for delegate definition. definition should not be stored and should be rejected with a
     * status code 400.
     *
     * @param context
     */
    @Test
    public void testDelegateRegistration_InvalidTransformationSpec(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        String delegate1 = DELEGATE_BASE + "/myEighthDelegate/definition";

        // prepare the delegate
        // -----
        JsonArray requests = new JsonArray();

        String invalidTransformationSpec = "[\n" +
                "  {\n" +
                "    \"spec\": {\n" +
                "      \"@\": \"records[0].value\"\n" +
                "    }\n" +
                "  }\n" +
                "]";

        JsonArray transformationSpec = new JsonArray(invalidTransformationSpec);
        long uniqueID = System.currentTimeMillis();
        JsonObject request = createRequest(new HashMap<>(), SERVER_ROOT + "/tests/delegate/" + uniqueID, HttpMethod.PUT, transformationSpec);
        requests.add(request);

        List<HttpMethod> methods = new ArrayList<>();
        methods.add(HttpMethod.PUT);

        JsonObject delegate = createDelegate(methods, ".*/([^/]+.*)", requests);
        System.out.println(delegate.toString());
        // -----

        // Registration
        given().body(delegate.toString()).put(delegate1).then().assertThat().statusCode(400);
        TestUtils.checkGETStatusCodeWithAwait(delegate1, 404);

        async.complete();
    }

    /**
     * Invalid payload property for delegate definition. Payload must be a json object. Definition should not be stored
     * and should be rejected with a status code 400.
     *
     * @param context
     */
    @Test
    public void testDelegateRegistration_InvalidPayloadDefinition(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        String delegate1 = DELEGATE_BASE + "/myEighthDelegate/definition";

        // prepare the delegate
        // -----
        JsonArray requests = new JsonArray();

        long uniqueID = System.currentTimeMillis();

        // payload must be a json object, not an array
        JsonArray invalidPayload = new JsonArray("[{\"foo\": \"bar\"}]");
        JsonObject request = createRequest(new HashMap<>(), SERVER_ROOT + "/tests/delegate/" + uniqueID, HttpMethod.PUT, new JsonObject());
        request.put("payload", invalidPayload);

        requests.add(request);

        List<HttpMethod> methods = new ArrayList<>();
        methods.add(HttpMethod.PUT);

        JsonObject delegate = createDelegate(methods, ".*/([^/]+.*)", requests);
        System.out.println(delegate.toString());
        // -----

        // Registration
        given().body(delegate.toString()).put(delegate1).then().assertThat().statusCode(400);
        TestUtils.checkGETStatusCodeWithAwait(delegate1, 404);

        async.complete();
    }

    /**
     * Define a delegate with a valid "payload" property request and a request with an invalid transformation spec
     * property. Definition should not be stored and should be rejected with a status code 400.
     *
     * @param context
     */
    @Test
    public void testDelegateRegistration_InvalidTransformationSpecMultipleRequests(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        String delegate1 = DELEGATE_BASE + "/myEighthDelegate/definition";

        // prepare the delegate
        // -----
        JsonArray requests = new JsonArray();

        String invalidTransformationSpec = "[\n" +
                "  {\n" +
                "    \"spec\": {\n" +
                "      \"@\": \"records[0].value\"\n" +
                "    }\n" +
                "  }\n" +
                "]";

        JsonArray transformationSpec = new JsonArray(invalidTransformationSpec);
        long uniqueID = System.currentTimeMillis();
        JsonObject request = createRequest(new HashMap<>(), SERVER_ROOT + "/tests/delegate/res1/" + uniqueID, HttpMethod.PUT, transformationSpec);

        JsonObject request2 = createRequest(new HashMap<>(), SERVER_ROOT + "/tests/delegate/res2/" + uniqueID, HttpMethod.PUT, new JsonObject("{\"foo\": \"bar\"}"));

        requests.add(request);
        requests.add(request2);

        List<HttpMethod> methods = new ArrayList<>();
        methods.add(HttpMethod.PUT);

        JsonObject delegate = createDelegate(methods, ".*/([^/]+.*)", requests);
        System.out.println(delegate.toString());
        // -----

        // Registration
        given().body(delegate.toString()).put(delegate1).then().assertThat().statusCode(400);
        TestUtils.checkGETStatusCodeWithAwait(delegate1, 404);

        async.complete();
    }

    /**
     * Define a delegate definition with a "payload" property request only. The delegate execution request does not contain
     * a (json) payload. This should be valid since the payload of the delegate execution request is not needed.
     *
     * @param context
     */
    @Test
    public void testDelegateExecution_NoTransformationNoRequestPayload(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        String delegate1 = DELEGATE_BASE + "/mySeventhDelegate/definition";
        String delegateExec1 = DELEGATE_BASE + "/mySeventhDelegate/execution";

        // prepare the delegate
        // -----
        JsonArray requests = new JsonArray();

        long uniqueID = System.currentTimeMillis();
        JsonObject request = createRequest(new HashMap<>(), SERVER_ROOT + "/tests/delegate/" + uniqueID, HttpMethod.PUT, new JsonObject("{\"foo\": \"bar\"}"));
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
        given().put(delegateExec1 + "/someRes").then().assertThat().statusCode(200); //no body defined
        TestUtils.checkGETStatusCodeWithAwait("/tests/delegate/" + uniqueID, 200);

        // Check if result is valid
        Response response = get("/tests/delegate/" + uniqueID);
        JsonObject transformedActual = new JsonObject(response.getBody().asString());

        JsonObject transformedExpected = new JsonObject("{\"foo\": \"bar\"}");

        context.assertEquals(200, response.getStatusCode());
        context.assertEquals(transformedExpected, transformedActual);

        // Unregistration
        delete(delegate1).then().assertThat().statusCode(200);
        TestUtils.checkGETStatusCodeWithAwait(delegate1, 404);

        async.complete();
    }

    /**
     * Define a delegate definition with a "payload" property request only. The delegate execution request does contain
     * an invalid json payload. This should be valid since the payload of the delegate execution request is not needed.
     *
     * @param context
     */
    @Test
    public void testDelegateExecution_NoTransformationInvalidRequestPayload(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        String delegate1 = DELEGATE_BASE + "/mySeventhDelegate/definition";
        String delegateExec1 = DELEGATE_BASE + "/mySeventhDelegate/execution";

        // prepare the delegate
        // -----
        JsonArray requests = new JsonArray();

        long uniqueID = System.currentTimeMillis();
        JsonObject request = createRequest(new HashMap<>(), SERVER_ROOT + "/tests/delegate/" + uniqueID, HttpMethod.PUT, new JsonObject("{\"foo\": \"bar\"}"));
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
        given().body("{\"key: 123}").put(delegateExec1 + "/someRes").then().assertThat().statusCode(200); //invalid json body defined
        TestUtils.checkGETStatusCodeWithAwait("/tests/delegate/" + uniqueID, 200);

        // Check if result is valid
        Response response = get("/tests/delegate/" + uniqueID);
        JsonObject transformedActual = new JsonObject(response.getBody().asString());

        JsonObject transformedExpected = new JsonObject("{\"foo\": \"bar\"}");

        context.assertEquals(200, response.getStatusCode());
        context.assertEquals(transformedExpected, transformedActual);

        // Unregistration
        delete(delegate1).then().assertThat().statusCode(200);
        TestUtils.checkGETStatusCodeWithAwait(delegate1, 404);

        async.complete();
    }

    /**
     * Define a delegate definition with a transformation spec request only. The delegate execution request does not contain
     * a (json) payload. The delegate execution request should be rejected, because the payload is used for the transformation.
     *
     * @param context
     */
    @Test
    public void testDelegateExecution_TransformationNoRequestPayload(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        String delegate1 = DELEGATE_BASE + "/mySeventhDelegate/definition";
        String delegateExec1 = DELEGATE_BASE + "/mySeventhDelegate/execution";

        // prepare the delegate
        // -----
        JsonArray requests = new JsonArray();

        String transformation = "[\n" +
                "  {\n" +
                "    \"operation\": \"shift\",\n" +
                "    \"spec\": {\n" +
                "      \"@\": \"records[0].value\"\n" +
                "    }\n" +
                "  }\n" +
                "]";

        long uniqueID = System.currentTimeMillis();
        JsonObject request = createRequest(new HashMap<>(), SERVER_ROOT + "/tests/delegate/" + uniqueID, HttpMethod.PUT, new JsonArray(transformation));
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
        given().put(delegateExec1 + "/someRes").then().assertThat().statusCode(400); //no body defined
        TestUtils.checkGETStatusCodeWithAwait("/tests/delegate/" + uniqueID, 404);

        // Unregistration
        delete(delegate1).then().assertThat().statusCode(200);
        TestUtils.checkGETStatusCodeWithAwait(delegate1, 404);

        async.complete();
    }

    /**
     * Define a delegate definition with a transformation spec request only. The delegate execution request does contain
     * an invalid json payload. The delegate execution request should be rejected, because a valid json payload is used for the transformation.
     *
     * @param context
     */
    @Test
    public void testDelegateExecution_TransformationInvalidRequestPayload(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        String delegate1 = DELEGATE_BASE + "/mySeventhDelegate/definition";
        String delegateExec1 = DELEGATE_BASE + "/mySeventhDelegate/execution";

        // prepare the delegate
        // -----
        JsonArray requests = new JsonArray();

        String transformation = "[\n" +
                "  {\n" +
                "    \"operation\": \"shift\",\n" +
                "    \"spec\": {\n" +
                "      \"@\": \"records[0].value\"\n" +
                "    }\n" +
                "  }\n" +
                "]";

        long uniqueID = System.currentTimeMillis();
        JsonObject request = createRequest(new HashMap<>(), SERVER_ROOT + "/tests/delegate/" + uniqueID, HttpMethod.PUT, new JsonArray(transformation));
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
        given().body("{\"key: 123}").put(delegateExec1 + "/someRes").then().assertThat().statusCode(400); //invalid json body defined
        TestUtils.checkGETStatusCodeWithAwait("/tests/delegate/" + uniqueID, 404);

        // Unregistration
        delete(delegate1).then().assertThat().statusCode(200);
        TestUtils.checkGETStatusCodeWithAwait(delegate1, 404);

        async.complete();
    }

    /**
     * Define a delegate definition with a transformation spec request and with a "payload" property request. The delegate execution request does not contain
     * a (json) payload. The delegate execution request should be rejected, because the payload is used for the transformation. The "payload" property request
     * should also not have been executed.
     *
     * @param context
     */
    @Test
    public void testDelegateExecution_MultipleRequestsIncludingTransformationNoRequestPayload(TestContext context) {
        Async async = context.async();
        delete();
        initRoutingRules();

        String delegate1 = DELEGATE_BASE + "/mySeventhDelegate/definition";
        String delegateExec1 = DELEGATE_BASE + "/mySeventhDelegate/execution";

        // prepare the delegate
        // -----
        JsonArray requests = new JsonArray();

        String transformation = "[\n" +
                "  {\n" +
                "    \"operation\": \"shift\",\n" +
                "    \"spec\": {\n" +
                "      \"@\": \"records[0].value\"\n" +
                "    }\n" +
                "  }\n" +
                "]";

        long uniqueID = System.currentTimeMillis();
        JsonObject request = createRequest(new HashMap<>(), SERVER_ROOT + "/tests/delegate/" + uniqueID, HttpMethod.PUT, new JsonArray(transformation));
        JsonObject request2 = createRequest(new HashMap<>(), SERVER_ROOT + "/tests/delegate/res2/" + uniqueID, HttpMethod.PUT, new JsonObject("{\"foo\": \"bar\"}"));
        requests.add(request);
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
        given().put(delegateExec1 + "/someRes").then().assertThat().statusCode(400); //no body defined
        TestUtils.checkGETStatusCodeWithAwait("/tests/delegate/" + uniqueID, 404);
        TestUtils.checkGETStatusCodeWithAwait("/tests/delegate/res2/" + uniqueID, 404);

        // Unregistration
        delete(delegate1).then().assertThat().statusCode(200);
        TestUtils.checkGETStatusCodeWithAwait(delegate1, 404);

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
     * @return
     */
    private JsonObject createRequest(Map<String, Object> headers, String uri, HttpMethod method) {
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
        return request;
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
        JsonObject request = createRequest(headers, uri, method);
        request.put("payload", payload);
        return request;
    }

    /**
     * Creates a new request with the given parameters.
     *
     * @param headers
     * @param uri
     * @param method
     * @param transform
     * @return
     */
    private JsonObject createRequest(Map<String, Object> headers, String uri, HttpMethod method, JsonArray transform) {
        JsonObject request = createRequest(headers, uri, method);
        request.put("transform", transform);
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

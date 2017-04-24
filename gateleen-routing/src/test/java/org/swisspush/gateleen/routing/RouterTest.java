package org.swisspush.gateleen.routing;

import com.google.common.collect.ImmutableMap;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.LocalMap;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.redis.RedisClient;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.http.DummyHttpServerResponse;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.logging.LoggingResource;
import org.swisspush.gateleen.logging.LoggingResourceManager;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Tests for the Router class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class RouterTest {

    private Vertx vertx;
    private Map<String, Object> properties;
    private LoggingResourceManager loggingResourceManager;
    private MonitoringHandler monitoringHandler;
    private RedisClient redisClient;
    private HttpClient httpClient;
    private String serverUrl;
    private String rulesPath;
    private String userProfilePath;
    private String randomResourcePath;
    private JsonObject info;
    private LocalMap<String, Object> routerStateMap;
    private ResourceStorage storage;

    private final String RULES_WITH_MISSING_PROPS = "{\n"
            + "  \"/gateleen/rule/1\": {\n"
            + "    \"description\": \"Test Rule 1\",\n"
            + "    \"url\": \"${gateleen.test.prop.1}/gateleen/rule/1\"\n"
            + "  },\n"
            + "  \"/gateleen/rule/2\": {\n"
            + "    \"description\": \"Test Rule 2\",\n"
            + "    \"url\": \"${gateleen.test.prop.2}/gateleen/rule/2\"\n"
            + "  }\n"
            + "}";

    private final String RULES_WITH_VALID_PROPS = "{\n"
            + "  \"/gateleen/rule/1\": {\n"
            + "    \"description\": \"Test Rule 1\",\n"
            + "    \"url\": \"${gateleen.test.prop.valid}/gateleen/rule/1\"\n"
            + "  },\n"
            + "  \"/gateleen/rule/2\": {\n"
            + "    \"description\": \"Test Rule 2\",\n"
            + "    \"url\": \"${gateleen.test.prop.valid}/gateleen/rule/2\"\n"
            + "  }\n"
            + "}";

    private final String RULES_WITH_HOPS = "{\n" +
            "  \"/gateleen/server/loop/1/(.*)\": {\n" +
            "    \"description\": \"looping Test\",\n" +
            "    \"metricName\": \"loop_1\",\n" +
            "    \"path\": \"/gateleen/server/loop/2/$1\"\n" +
            "  },\n" +
            "  \"/gateleen/server/loop/2/(.*)\": {\n" +
            "    \"description\": \"looping Test\",\n" +
            "    \"metricName\": \"loop_2\",\n" +
            "    \"path\": \"/gateleen/server/loop/3/$1\"\n" +
            "  },\n" +
            "  \"/gateleen/server/loop/3/(.*)\": {\n" +
            "    \"description\": \"looping Test\",\n" +
            "    \"metricName\": \"loop_3\",\n" +
            "    \"path\": \"/gateleen/server/loop/4/$1\"\n" +
            "  },\n" +
            "  \"/gateleen/server/loop/4/(.*)\": {\n" +
            "    \"description\": \"looping Test\",\n" +
            "    \"metricName\": \"loop_4\",\n" +
            "    \"path\": \"/gatelee/servern/loop/4/$1\",\n" +
            "    \"storage\": \"main\"\n" +
            "  },\n" +
            "  \"/gateleen/server/looping/(.*)\": {\n" +
            "    \"description\": \"looping Test\",\n" +
            "    \"metricName\": \"looperRule\",\n" +
            "    \"path\": \"/gateleen/server/looping/$1\"\n" +
            "\n" +
            "  }\n" +
            "}";

    private final String RANDOM_RESOURCE = "{\n"
            + "  \"randomkey1\": 123,\n"
            + "  \"randomkey2\": 456\n"
            + "}";

    @Before
    public void setUp(){
        // setup
        vertx = Mockito.mock(Vertx.class);
        Mockito.when(vertx.eventBus()).thenReturn(Mockito.mock(EventBus.class));
        Mockito.when(vertx.createHttpClient()).thenReturn(Mockito.mock(HttpClient.class));

        properties = new HashMap<>();
        properties.put("gateleen.test.prop.valid", "http://someserver/");
        loggingResourceManager = Mockito.mock(LoggingResourceManager.class);
        Mockito.when(loggingResourceManager.getLoggingResource()).thenReturn(new LoggingResource());
        redisClient = Mockito.mock(RedisClient.class);
        monitoringHandler = Mockito.mock(MonitoringHandler.class);
        httpClient = Mockito.mock(HttpClient.class);

        serverUrl = "/gateleen/server";
        rulesPath = serverUrl + "/admin/v1/routing/rules";
        randomResourcePath = serverUrl + "/random/resource";
        userProfilePath = serverUrl + "/users/v1/%s/profile";
        info = new JsonObject();

        storage = new MockResourceStorage(ImmutableMap.of(rulesPath, RULES_WITH_MISSING_PROPS, randomResourcePath, RANDOM_RESOURCE));

        routerStateMap = new DummyLocalMap<>();
    }

    @Test
    public void testRequestHopValidationLimitNotYetReached(TestContext context){
        storage = new MockResourceStorage(ImmutableMap.of(rulesPath, RULES_WITH_HOPS, serverUrl + "/loop/4/resource", RANDOM_RESOURCE));
        Router router = new Router(vertx, routerStateMap, storage, properties, loggingResourceManager, monitoringHandler, httpClient, serverUrl, rulesPath, userProfilePath, info);

        ConfigurationResourceManager configurationResourceManager = Mockito.mock(ConfigurationResourceManager.class);
        router.enableRoutingConfiguration(configurationResourceManager, serverUrl + "/admin/v1/routing/config");

        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");

        // change the hops limit to 5
        router.resourceChanged(serverUrl + "/admin/v1/routing/config", "{\"request.hops.limit\":5}");

        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        response.setStatusCode(StatusCode.OK.getStatusCode());
        response.setStatusMessage(StatusCode.OK.getStatusMessage());
        class GETRandomResourceRequest extends DummyHttpServerRequest{

            MultiMap headers = new CaseInsensitiveHeaders();

            @Override public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override public String uri() {
                return "/gateleen/server/loop/4/resource";
            }

            @Override public String path() { return "/gateleen/server/loop/4/resource"; }

            @Override public MultiMap headers() { return headers; }

            @Override public MultiMap params() { return new CaseInsensitiveHeaders(); }

            @Override
            public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
                bodyHandler.handle(Buffer.buffer(RANDOM_RESOURCE));
                return this;
            }

            @Override
            public HttpServerRequest handler(Handler<Buffer> handler) {
                handler.handle(Buffer.buffer(RANDOM_RESOURCE));
                return this;
            }

            @Override
            public HttpServerRequest endHandler(Handler<Void> endHandler) {
                endHandler.handle(null);
                return this;
            }

            @Override
            public DummyHttpServerResponse response() {
                return response;
            }
        }
        GETRandomResourceRequest request = new GETRandomResourceRequest();
        router.route(request);

        context.assertEquals("1", request.headers().get("x-hops"), "x-hops header should have value 1");
        context.assertEquals(StatusCode.OK.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 200");
        context.assertEquals(StatusCode.OK.getStatusMessage(), request.response().getStatusMessage(), "StatusMessage should be OK");
    }

    @Test
    public void testRequestHopValidationWithLimitZero(TestContext context){
        storage = new MockResourceStorage(ImmutableMap.of(rulesPath, RULES_WITH_HOPS, serverUrl + "/loop/4/resource", RANDOM_RESOURCE));
        Router router = new Router(vertx, routerStateMap, storage, properties, loggingResourceManager, monitoringHandler, httpClient, serverUrl, rulesPath, userProfilePath, info);

        ConfigurationResourceManager configurationResourceManager = Mockito.mock(ConfigurationResourceManager.class);
        router.enableRoutingConfiguration(configurationResourceManager, serverUrl + "/admin/v1/routing/config");

        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");

        // change the hops limit to 0, so no re-routing is allowed
        router.resourceChanged(serverUrl + "/admin/v1/routing/config", "{\"request.hops.limit\":0}");

        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        response.setStatusCode(StatusCode.OK.getStatusCode());
        response.setStatusMessage(StatusCode.OK.getStatusMessage());
        class GETRandomResourceRequest extends DummyHttpServerRequest{

            MultiMap headers = new CaseInsensitiveHeaders();

            @Override public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override public String uri() {
                return "/gateleen/server/loop/4/resource";
            }

            @Override public String path() { return "/gateleen/server/loop/4/resource"; }

            @Override public MultiMap headers() { return headers; }

            @Override public MultiMap params() { return new CaseInsensitiveHeaders(); }

            @Override
            public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
                bodyHandler.handle(Buffer.buffer(RANDOM_RESOURCE));
                return this;
            }

            @Override
            public HttpServerRequest handler(Handler<Buffer> handler) {
                handler.handle(Buffer.buffer(RANDOM_RESOURCE));
                return this;
            }

            @Override
            public HttpServerRequest endHandler(Handler<Void> endHandler) {
                endHandler.handle(null);
                return this;
            }

            @Override
            public DummyHttpServerResponse response() {
                return response;
            }
        }
        GETRandomResourceRequest request = new GETRandomResourceRequest();
        router.route(request);

        context.assertEquals("1", request.headers().get("x-hops"), "x-hops header should have value 1");
        context.assertEquals(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 500");
        context.assertEquals("Request hops limit exceeded", request.response().getStatusMessage(), "StatusMessage should be 'Request hops limit exceeded'");
    }

    @Test
    public void testRequestHopValidationWithLimit5(TestContext context){
        storage = new MockResourceStorage(ImmutableMap.of(rulesPath, RULES_WITH_HOPS, serverUrl + "/loop/4/resource", RANDOM_RESOURCE));
        Router router = new Router(vertx, routerStateMap, storage, properties, loggingResourceManager, monitoringHandler, httpClient, serverUrl, rulesPath, userProfilePath, info);

        ConfigurationResourceManager configurationResourceManager = Mockito.mock(ConfigurationResourceManager.class);
        router.enableRoutingConfiguration(configurationResourceManager, serverUrl + "/admin/v1/routing/config");

        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");

        // change the hops limit to 5
        router.resourceChanged(serverUrl + "/admin/v1/routing/config", "{\"request.hops.limit\":5}");

        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        response.setStatusCode(StatusCode.OK.getStatusCode());
        response.setStatusMessage(StatusCode.OK.getStatusMessage());
        class GETRandomResourceRequest extends DummyHttpServerRequest{

            MultiMap headers = new CaseInsensitiveHeaders();

            @Override public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override public String uri() {
                return "/gateleen/server/loop/4/resource";
            }

            @Override public String path() { return "/gateleen/server/loop/4/resource"; }

            @Override public MultiMap headers() { return headers; }

            @Override public MultiMap params() { return new CaseInsensitiveHeaders(); }

            @Override
            public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
                bodyHandler.handle(Buffer.buffer(RANDOM_RESOURCE));
                return this;
            }

            @Override
            public HttpServerRequest handler(Handler<Buffer> handler) {
                handler.handle(Buffer.buffer(RANDOM_RESOURCE));
                return this;
            }

            @Override
            public HttpServerRequest endHandler(Handler<Void> endHandler) {
                endHandler.handle(null);
                return this;
            }

            @Override
            public DummyHttpServerResponse response() {
                return response;
            }
        }
        GETRandomResourceRequest request = new GETRandomResourceRequest();

        for (int i = 0; i < 5; i++) {
            router.route(request);
        }
        context.assertEquals("5", request.headers().get("x-hops"), "x-hops header should have value 5");
        context.assertEquals(StatusCode.OK.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 200");
        context.assertEquals(StatusCode.OK.getStatusMessage(), request.response().getStatusMessage(), "StatusMessage should be OK");

        router.route(request);
        context.assertEquals("6", request.headers().get("x-hops"), "x-hops header should have value 6");
        context.assertEquals(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 500");
        context.assertEquals("Request hops limit exceeded", request.response().getStatusMessage(), "StatusMessage should be 'Request hops limit exceeded'");
    }

    @Test
    public void testRequestHopValidationNoLimitConfiguration(TestContext context){
        storage = new MockResourceStorage(ImmutableMap.of(rulesPath, RULES_WITH_HOPS, serverUrl + "/loop/4/resource", RANDOM_RESOURCE));
        Router router = new Router(vertx, routerStateMap, storage, properties, loggingResourceManager, monitoringHandler, httpClient, serverUrl, rulesPath, userProfilePath, info);

        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");

        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        response.setStatusCode(StatusCode.OK.getStatusCode());
        response.setStatusMessage(StatusCode.OK.getStatusMessage());
        class GETRandomResourceRequest extends DummyHttpServerRequest{

            MultiMap headers = new CaseInsensitiveHeaders();

            @Override public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override public String uri() {
                return "/gateleen/server/loop/4/resource";
            }

            @Override public String path() { return "/gateleen/server/loop/4/resource"; }

            @Override public MultiMap headers() { return headers; }

            @Override public MultiMap params() { return new CaseInsensitiveHeaders(); }

            @Override
            public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
                bodyHandler.handle(Buffer.buffer(RANDOM_RESOURCE));
                return this;
            }

            @Override
            public HttpServerRequest handler(Handler<Buffer> handler) {
                handler.handle(Buffer.buffer(RANDOM_RESOURCE));
                return this;
            }

            @Override
            public HttpServerRequest endHandler(Handler<Void> endHandler) {
                endHandler.handle(null);
                return this;
            }

            @Override
            public DummyHttpServerResponse response() {
                return response;
            }
        }
        GETRandomResourceRequest request = new GETRandomResourceRequest();

        for (int i = 0; i < 20; i++) {
            router.route(request);
        }

        context.assertNull(request.headers().get("x-hops"), "No x-hops header should be present");
        context.assertEquals(StatusCode.OK.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 200");
        context.assertEquals(StatusCode.OK.getStatusMessage(), request.response().getStatusMessage(), "StatusMessage should be OK");
    }


    @Test
    public void testRouterConstructionValidConfiguration(TestContext context){
        properties.put("gateleen.test.prop.1", "http://someserver/");
        properties.put("gateleen.test.prop.2", "http://someserver/");
        Router router = new Router(vertx, routerStateMap, storage, properties, loggingResourceManager, monitoringHandler, httpClient, serverUrl, rulesPath, userProfilePath, info);
        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");
    }

    @Test
    public void testRouterConstructionWithMissingProperty(TestContext context){
        Router router = new Router(vertx, routerStateMap, storage, properties, loggingResourceManager, monitoringHandler, httpClient, serverUrl, rulesPath, userProfilePath, info);
        context.assertTrue(router.isRoutingBroken(), "Routing should be broken because of missing properties entry");
        context.assertNotNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");
        context.assertTrue(router.getRoutingBrokenMessage().contains("gateleen.test.prop.1"), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");
    }

    @Test
    public void testFixBrokenRouting(TestContext context){
        Router router = new Router(vertx, routerStateMap, storage, properties, loggingResourceManager, monitoringHandler, httpClient, serverUrl, rulesPath, userProfilePath, info);
        context.assertTrue(router.isRoutingBroken(), "Routing should be broken because of missing properties entry");
        context.assertNotNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");
        context.assertTrue(router.getRoutingBrokenMessage().contains("gateleen.test.prop.1"), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");

        // fix routing by passing a valid rules resource via PUT request
        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        class UpdateRulesWithValidResourceRequest extends DummyHttpServerRequest{
            @Override public HttpMethod method() {
                return HttpMethod.PUT;
            }

            @Override public String uri() {
                return "/gateleen/server/admin/v1/routing/rules";
            }

            @Override
            public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
                bodyHandler.handle(Buffer.buffer(RULES_WITH_VALID_PROPS));
                return this;
            }

            @Override
            public HttpServerResponse response() {
                return response;
            }
        }

        router.route(new UpdateRulesWithValidResourceRequest());
        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken anymore");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");
    }

    @Test
    public void testGETRoutingRulesWithBrokenRouterShouldWork(TestContext context){
        Router router = new Router(vertx, routerStateMap, storage, properties, loggingResourceManager, monitoringHandler, httpClient, serverUrl, rulesPath, userProfilePath, info);
        context.assertTrue(router.isRoutingBroken(), "Routing should be broken because of missing properties entry");
        context.assertNotNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");
        context.assertTrue(router.getRoutingBrokenMessage().contains("gateleen.test.prop.1"), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");

        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        class GETRoutingRulesRequest extends DummyHttpServerRequest{
            @Override public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override public String uri() {
                return "/gateleen/server/admin/v1/routing/rules";
            }

            @Override
            public DummyHttpServerResponse response() {
                return response;
            }
        }
        GETRoutingRulesRequest request = new GETRoutingRulesRequest();
        router.route(request);
        context.assertTrue(router.isRoutingBroken(), "Routing should still be broken");
        context.assertNotNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");
        context.assertTrue(router.getRoutingBrokenMessage().contains("gateleen.test.prop.1"), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");

        context.assertEquals(StatusCode.OK.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 200");
        context.assertEquals(StatusCode.OK.getStatusMessage(), request.response().getStatusMessage(), "StatusMessage should be OK");
        context.assertEquals(RULES_WITH_MISSING_PROPS, request.response().getResultBuffer(), "RoutingRules should be returned as result");
    }

    @Test
    public void testGETAnyResourceWithBrokenRouterShouldNotWork(TestContext context){
        Router router = new Router(vertx, routerStateMap, storage, properties, loggingResourceManager, monitoringHandler, httpClient, serverUrl, rulesPath, userProfilePath, info);
        context.assertTrue(router.isRoutingBroken(), "Routing should be broken because of missing properties entry");
        context.assertNotNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");
        context.assertTrue(router.getRoutingBrokenMessage().contains("gateleen.test.prop.1"), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");

        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        class GETRandomResourceRequest extends DummyHttpServerRequest{
            @Override public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override public String uri() {
                return "/gateleen/server/random/resource";
            }

            @Override
            public DummyHttpServerResponse response() {
                return response;
            }
        }
        GETRandomResourceRequest request = new GETRandomResourceRequest();
        router.route(request);
        context.assertTrue(router.isRoutingBroken(), "Routing should still be broken");
        context.assertNotNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");
        context.assertTrue(router.getRoutingBrokenMessage().contains("gateleen.test.prop.1"), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");

        context.assertEquals(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 500");
        context.assertEquals(StatusCode.INTERNAL_SERVER_ERROR.getStatusMessage(), request.response().getStatusMessage(), "StatusMessage should be Internal Server Error");
        context.assertTrue(request.response().getResultBuffer().contains("Routing is broken"), "Routing is broken message should be returned");
        context.assertTrue(request.response().getResultBuffer().contains("gateleen.test.prop.1"), "The message should contain 'gateleen.test.prop.1' in the message");
    }

    @Test
    public void testGETAnyResourceAfterFixingBrokenRouterShouldWorkAgain(TestContext context){
        Router router = new Router(vertx, routerStateMap, storage, properties, loggingResourceManager, monitoringHandler, httpClient, serverUrl, rulesPath, userProfilePath, info);
        context.assertTrue(router.isRoutingBroken(), "Routing should be broken because of missing properties entry");
        context.assertNotNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");
        context.assertTrue(router.getRoutingBrokenMessage().contains("gateleen.test.prop.1"), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");

        // get random resource with broken routing. Should not work
        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        class GETRandomResourceRequest extends DummyHttpServerRequest{
            @Override public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override public String uri() {
                return "/gateleen/server/random/resource";
            }

            @Override public String path() {
                return "/gateleen/server/random/resource";
            }

            @Override
            public DummyHttpServerResponse response() {
                return response;
            }
        }
        GETRandomResourceRequest request = new GETRandomResourceRequest();
        router.route(request);
        context.assertTrue(router.isRoutingBroken(), "Routing should still be broken");
        context.assertNotNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");
        context.assertTrue(router.getRoutingBrokenMessage().contains("gateleen.test.prop.1"), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");

        context.assertEquals(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 500");
        context.assertEquals(StatusCode.INTERNAL_SERVER_ERROR.getStatusMessage(), request.response().getStatusMessage(), "StatusMessage should be Internal Server Error");
        context.assertTrue(request.response().getResultBuffer().contains("Routing is broken"), "Routing is broken message should be returned");
        context.assertTrue(request.response().getResultBuffer().contains("gateleen.test.prop.1"), "The message should contain 'gateleen.test.prop.1' in the message");

        // fix routing
        final DummyHttpServerResponse responseFix = new DummyHttpServerResponse();
        class UpdateRulesWithValidResourceRequest extends DummyHttpServerRequest{
            @Override public HttpMethod method() {
                return HttpMethod.PUT;
            }

            @Override public String uri() {
                return "/gateleen/server/admin/v1/routing/rules";
            }

            @Override
            public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
                bodyHandler.handle(Buffer.buffer(RULES_WITH_VALID_PROPS));
                return this;
            }

            @Override
            public HttpServerResponse response() {
                return responseFix;
            }
        }
        router.route(new UpdateRulesWithValidResourceRequest());
        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken anymore");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");

        // retry get random resource. Should work now
        final DummyHttpServerResponse responseRandomResource = new DummyHttpServerResponse();
        class GETRandomResourceAgainRequest extends DummyHttpServerRequest {
            @Override public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override public String uri() {
                return "/gateleen/server/random/resource";
            }

            @Override public String path() { return "/gateleen/server/random/resource"; }

            @Override public MultiMap params() { return new CaseInsensitiveHeaders(); }

            @Override public MultiMap headers() { return new CaseInsensitiveHeaders(); }

            @Override
            public DummyHttpServerResponse response() {
                return responseRandomResource;
            }
        }
        GETRandomResourceAgainRequest requestRandomResource = new GETRandomResourceAgainRequest();
        router.route(requestRandomResource);
        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken anymore");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");
    }

    private class DummyLocalMap<K, V> implements LocalMap<String, Object> {

        private Map<String, Object> map = new HashMap<>();

        @Override
        public Object get(String key) {
            return map.get(key);
        }

        @Override
        public Object put(String key, Object value) {
            return map.put(key, value);
        }

        @Override
        public Object remove(String key) {
            return map.remove(key);
        }

        @Override
        public void clear() {
            map.clear();
        }

        @Override
        public int size() {
            return map.size();
        }

        @Override
        public boolean isEmpty() {
            return map.isEmpty();
        }

        @Override
        public Object putIfAbsent(String key, Object value) {
            if(!map.containsKey(key)){
                map.put(key, value);
                return null;
            }
            return map.get(key);
        }

        @Override
        public boolean removeIfPresent(String key, Object value) {
            if(map.containsKey(key)){
                map.remove(key);
                return true;
            }
            return false;
        }

        @Override
        public boolean replaceIfPresent(String key, Object oldValue, Object newValue) {
            if(map.containsKey(key) && map.get(key).equals(oldValue)){
                map.put(key, newValue);
                return true;
            }
            return false;
        }

        @Override
        public Object replace(String key, Object value) {
            return map.replace(key, value);
        }

        @Override
        public void close() {}

        @Override
        public Set<String> keySet() {
            return map.keySet();
        }

        @Override
        public Collection<Object> values() {
            return map.values();
        }
    }

}

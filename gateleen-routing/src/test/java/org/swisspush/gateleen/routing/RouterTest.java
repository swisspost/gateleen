package org.swisspush.gateleen.routing;

import com.google.common.collect.ImmutableMap;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.*;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.http.DummyHttpServerResponse;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.logging.LoggingResource;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.monitoring.MonitoringHandler;

import java.util.*;

import static org.mockito.Mockito.doAnswer;

/**
 * Tests for the Router class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class RouterTest {

    private Vertx vertx;
    private EventBus eventBus;
    private Message<Object> message;

    private Map<String, Object> properties;
    private LoggingResourceManager loggingResourceManager;
    private MonitoringHandler monitoringHandler;
    private MeterRegistry meterRegistry;
    private HttpClient httpClient;
    private String serverUrl;
    private String rulesPath;
    private String userProfilePath;
    private String randomResourcePath;
    private JsonObject info;
    private ResourceStorage storage;
    private final int storagePort = 8989;

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

    private final String RULES_WITH_HEADERSFILTER = "{\n" +
            "  \"/gateleen/server/forward/to/backend\": {\n" +
            "    \"metricName\": \"forward_backend\",\n" +
            "    \"headersFilter\": \"x-foo: (A|B|C)\",\n" +
            "    \"url\": \"http://localhost/some/backend/path\"\n" +
            "  },\n" +
            "  \"/gateleen/server/forward/to/storage\": {\n" +
            "    \"metricName\": \"forward_storage\",\n" +
            "    \"headersFilter\": \"x-foo: (.*)\",\n" +
            "    \"path\": \"/gateleen/storage/resource_x\",\n" +
            "    \"storage\": \"main\"\n" +
            "  },\n" +
            "  \"/gateleen/server/forward/to/nowhere\": {\n" +
            "    \"metricName\": \"forward_null\",\n" +
            "    \"headersFilter\": \"x-foo: [0-9]{3}\"\n" +
            "  }\n" +
            "}";

    private final String RANDOM_RESOURCE = "{\n"
            + "  \"randomkey1\": 123,\n"
            + "  \"randomkey2\": 456\n"
            + "}";

    private byte[] eventBusMessageAsByteArray = {0, 0, 0, 52, 123, 34, 115, 116, 97, 116, 117, 115, 67, 111, 100, 101, 34,
            58, 50, 48, 48, 44, 34, 115, 116, 97, 116, 117, 115, 77, 101, 115, 115, 97, 103, 101, 34, 58, 34, 79, 75, 34,
            44, 34, 104, 101, 97, 100, 101, 114, 115, 34, 58, 91, 93, 125};

    @Before
    public void setUp() {
        // setup
        vertx = Mockito.mock(Vertx.class);
        eventBus = Mockito.mock(EventBus.class);
        message = Mockito.mock(Message.class);

        Mockito.when(message.body()).thenReturn(Buffer.buffer(eventBusMessageAsByteArray));

        doAnswer(invocation -> {
            Handler<AsyncResult<Message<Object>>> handler = (Handler<AsyncResult<Message<Object>>>) invocation.getArguments()[3];
            handler.handle(Future.succeededFuture(message));
            return null;
        }).when(eventBus).request(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any());

        Mockito.when(vertx.eventBus()).thenReturn(eventBus);
        Mockito.when(vertx.createHttpClient()).thenReturn(Mockito.mock(HttpClient.class));
        Mockito.when(vertx.sharedData()).thenReturn(Vertx.vertx().sharedData());

        properties = new HashMap<>();
        properties.put("gateleen.test.prop.valid", "http://someserver/");
        loggingResourceManager = Mockito.mock(LoggingResourceManager.class);
        Mockito.when(loggingResourceManager.getLoggingResource()).thenReturn(new LoggingResource());
        monitoringHandler = Mockito.mock(MonitoringHandler.class);
        httpClient = Mockito.mock(HttpClient.class);
        meterRegistry = new SimpleMeterRegistry();

        serverUrl = "/gateleen/server";
        rulesPath = serverUrl + "/admin/v1/routing/rules";
        randomResourcePath = serverUrl + "/random/resource";
        userProfilePath = serverUrl + "/users/v1/%s/profile";
        info = new JsonObject();

        storage = new MockResourceStorage(ImmutableMap.of(rulesPath, RULES_WITH_MISSING_PROPS, randomResourcePath, RANDOM_RESOURCE));
    }

    private RouterBuilder routerBuilder() {
        return Router.builder()
                .withVertx(vertx)
                .withStorage(storage)
                .withProperties(properties)
                .withLoggingResourceManager(loggingResourceManager)
                .withMonitoringHandler(monitoringHandler)
                .withMeterRegistry(meterRegistry)
                .withSelfClient(httpClient)
                .withServerPath(serverUrl)
                .withRulesPath(rulesPath)
                .withUserProfilePath(userProfilePath)
                .withInfo(info)
                .withStoragePort(storagePort);
    }

    private void assertNoCountersIncremented(TestContext context) {
        for (Timer timer : meterRegistry.get(AbstractForwarder.FORWARDS_METRIC_NAME).timers()) {
            context.assertEquals(0L, timer.count(), "No timer should have been incremented");
        }
    }

    @Test
    public void testRequestHopValidationLimitNotYetReached(TestContext context) {
        storage = new MockResourceStorage(ImmutableMap.of(rulesPath, RULES_WITH_HOPS, serverUrl + "/loop/4/resource", RANDOM_RESOURCE));
        Router router = routerBuilder().withStorage(storage).build();

        ConfigurationResourceManager configurationResourceManager = Mockito.mock(ConfigurationResourceManager.class);
        router.enableRoutingConfiguration(configurationResourceManager, serverUrl + "/admin/v1/routing/config");

        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");

        // change the hops limit to 5
        router.resourceChanged(serverUrl + "/admin/v1/routing/config", Buffer.buffer("{\"request.hops.limit\":5}"));

        DummyHttpServerResponse response = new DummyHttpServerResponse() {
            @Override
            public Future<Void> write(Buffer data) {
                return Future.succeededFuture();
            }
        };
        response.setStatusCode(StatusCode.OK.getStatusCode());
        response.setStatusMessage(StatusCode.OK.getStatusMessage());

        class GETRandomResourceRequest extends DummyHttpServerRequest {

            MultiMap headers = MultiMap.caseInsensitiveMultiMap();

            @Override
            public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override
            public String uri() {
                return "/gateleen/server/loop/4/resource";
            }

            @Override
            public String path() {
                return "/gateleen/server/loop/4/resource";
            }

            @Override
            public MultiMap headers() {
                return headers;
            }

            @Override
            public MultiMap params() {
                return MultiMap.caseInsensitiveMultiMap();
            }

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

        Timer timer = meterRegistry.get(AbstractForwarder.FORWARDS_METRIC_NAME).tag(AbstractForwarder.FORWARDER_METRIC_TAG_METRICNAME, "loop_4").timer();
        context.assertEquals(1L, timer.count(), "Timer for `loop_4` rule should have been incremented by 1");

        context.assertEquals("1", request.headers().get("x-hops"), "x-hops header should have value 1");
        context.assertEquals(StatusCode.OK.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 200");
        context.assertEquals(StatusCode.OK.getStatusMessage(), request.response().getStatusMessage(), "StatusMessage should be OK");
    }

    @Test
    public void testRequestHopValidationWithLimitZero(TestContext context) {
        storage = new MockResourceStorage(ImmutableMap.of(rulesPath, RULES_WITH_HOPS, serverUrl + "/loop/4/resource", RANDOM_RESOURCE));
        Router router = routerBuilder().withStorage(storage).build();

        ConfigurationResourceManager configurationResourceManager = Mockito.mock(ConfigurationResourceManager.class);
        router.enableRoutingConfiguration(configurationResourceManager, serverUrl + "/admin/v1/routing/config");

        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");

        // change the hops limit to 0, so no re-routing is allowed
        router.resourceChanged(serverUrl + "/admin/v1/routing/config", Buffer.buffer("{\"request.hops.limit\":0}"));

        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        response.setStatusCode(StatusCode.OK.getStatusCode());
        response.setStatusMessage(StatusCode.OK.getStatusMessage());
        class GETRandomResourceRequest extends DummyHttpServerRequest {

            MultiMap headers = MultiMap.caseInsensitiveMultiMap();

            @Override
            public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override
            public String uri() {
                return "/gateleen/server/loop/4/resource";
            }

            @Override
            public String path() {
                return "/gateleen/server/loop/4/resource";
            }

            @Override
            public MultiMap headers() {
                return headers;
            }

            @Override
            public MultiMap params() {
                return MultiMap.caseInsensitiveMultiMap();
            }

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

        assertNoCountersIncremented(context);

        context.assertEquals("1", request.headers().get("x-hops"), "x-hops header should have value 1");
        context.assertEquals(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 500");
        context.assertEquals("Request hops limit exceeded", request.response().getStatusMessage(), "StatusMessage should be 'Request hops limit exceeded'");
    }

    @Test
    public void testRequestHopValidationWithLimit5(TestContext context) {
        storage = new MockResourceStorage(ImmutableMap.of(rulesPath, RULES_WITH_HOPS, serverUrl + "/loop/4/resource", RANDOM_RESOURCE));
        Router router = routerBuilder().withStorage(storage).build();

        ConfigurationResourceManager configurationResourceManager = Mockito.mock(ConfigurationResourceManager.class);
        router.enableRoutingConfiguration(configurationResourceManager, serverUrl + "/admin/v1/routing/config");

        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");

        // change the hops limit to 5
        router.resourceChanged(serverUrl + "/admin/v1/routing/config", Buffer.buffer("{\"request.hops.limit\":5}"));

        final DummyHttpServerResponse response = new DummyHttpServerResponse() {
            @Override
            public Future<Void> write(Buffer data) {
                return Future.succeededFuture();
            }
        };
        response.setStatusCode(StatusCode.OK.getStatusCode());
        response.setStatusMessage(StatusCode.OK.getStatusMessage());
        class GETRandomResourceRequest extends DummyHttpServerRequest {

            MultiMap headers = MultiMap.caseInsensitiveMultiMap();

            @Override
            public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override
            public String uri() {
                return "/gateleen/server/loop/4/resource";
            }

            @Override
            public String path() {
                return "/gateleen/server/loop/4/resource";
            }

            @Override
            public MultiMap headers() {
                return headers;
            }

            @Override
            public MultiMap params() {
                return MultiMap.caseInsensitiveMultiMap();
            }

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

        Timer timer = meterRegistry.get(AbstractForwarder.FORWARDS_METRIC_NAME).tag(AbstractForwarder.FORWARDER_METRIC_TAG_METRICNAME, "loop_4").timer();
        context.assertEquals(5L, timer.count(), "Timer for `loop_4` rule should have been incremented by 5");
    }

    @Test
    public void testRequestHopValidationNoLimitConfiguration(TestContext context) {
        storage = new MockResourceStorage(ImmutableMap.of(rulesPath, RULES_WITH_HOPS, serverUrl + "/loop/4/resource", RANDOM_RESOURCE));
        Router router = routerBuilder().withStorage(storage).build();

        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");

        final DummyHttpServerResponse response = new DummyHttpServerResponse() {
            @Override
            public Future<Void> write(Buffer data) {
                return Future.succeededFuture();
            }
        };
        response.setStatusCode(StatusCode.OK.getStatusCode());
        response.setStatusMessage(StatusCode.OK.getStatusMessage());
        class GETRandomResourceRequest extends DummyHttpServerRequest {

            MultiMap headers = MultiMap.caseInsensitiveMultiMap();

            @Override
            public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override
            public String uri() {
                return "/gateleen/server/loop/4/resource";
            }

            @Override
            public String path() {
                return "/gateleen/server/loop/4/resource";
            }

            @Override
            public MultiMap headers() {
                return headers;
            }

            @Override
            public MultiMap params() {
                return MultiMap.caseInsensitiveMultiMap();
            }

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

        Timer timer = meterRegistry.get(AbstractForwarder.FORWARDS_METRIC_NAME).tag(AbstractForwarder.FORWARDER_METRIC_TAG_METRICNAME, "loop_4").timer();
        context.assertEquals(20L, timer.count(), "Timer for `loop_4` rule should have been incremented by 20");
    }


    @Test
    public void testRouterConstructionValidConfiguration(TestContext context) {
        properties.put("gateleen.test.prop.1", "http://someserver/");
        properties.put("gateleen.test.prop.2", "http://someserver/");
        Router router = routerBuilder().withProperties(properties).build();
        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");

        assertNoCountersIncremented(context);
    }

    @Test
    public void testRouterConstructionWithMissingProperty(TestContext context) {
        Router router = routerBuilder().build();
        context.assertTrue(router.isRoutingBroken(), "Routing should be broken because of missing properties entry");
        context.assertNotNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");
        context.assertTrue(router.getRoutingBrokenMessage().contains("gateleen.test.prop.1"), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");

        assertNoCountersIncremented(context);
    }

    @Test
    public void testFixBrokenRouting(TestContext context) {
        Router router = routerBuilder().build();
        context.assertTrue(router.isRoutingBroken(), "Routing should be broken because of missing properties entry");
        context.assertNotNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");
        context.assertTrue(router.getRoutingBrokenMessage().contains("gateleen.test.prop.1"), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");

        // fix routing by passing a valid rules resource via PUT request
        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        class UpdateRulesWithValidResourceRequest extends DummyHttpServerRequest {
            @Override
            public HttpMethod method() {
                return HttpMethod.PUT;
            }

            @Override
            public String uri() {
                return "/gateleen/server/admin/v1/routing/rules";
            }

            @Override
            public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
                bodyHandler.handle(Buffer.buffer(RULES_WITH_VALID_PROPS));
                return this;
            }

            @Override
            public MultiMap headers() {
                return MultiMap.caseInsensitiveMultiMap();
            }

            @Override
            public HttpServerResponse response() {
                return response;
            }
        }

        router.route(new UpdateRulesWithValidResourceRequest());
        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken anymore");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");

        assertNoCountersIncremented(context);
    }

    @Test
    public void testGETRoutingRulesWithBrokenRouterShouldWork(TestContext context) {
        Router router = routerBuilder().build();
        context.assertTrue(router.isRoutingBroken(), "Routing should be broken because of missing properties entry");
        context.assertNotNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");
        context.assertTrue(router.getRoutingBrokenMessage().contains("gateleen.test.prop.1"), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");

        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        class GETRoutingRulesRequest extends DummyHttpServerRequest {
            @Override
            public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override
            public String uri() {
                return "/gateleen/server/admin/v1/routing/rules";
            }

            @Override
            public DummyHttpServerResponse response() {
                return response;
            }

            @Override
            public MultiMap headers() {
                return MultiMap.caseInsensitiveMultiMap();
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

        assertNoCountersIncremented(context);
    }

    @Test
    public void testGETAnyResourceWithBrokenRouterShouldNotWork(TestContext context) {
        Router router = routerBuilder().build();
        context.assertTrue(router.isRoutingBroken(), "Routing should be broken because of missing properties entry");
        context.assertNotNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");
        context.assertTrue(router.getRoutingBrokenMessage().contains("gateleen.test.prop.1"), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");

        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        class GETRandomResourceRequest extends DummyHttpServerRequest {
            @Override
            public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override
            public String uri() {
                return "/gateleen/server/random/resource";
            }

            @Override
            public DummyHttpServerResponse response() {
                return response;
            }

            @Override
            public MultiMap headers() {
                return MultiMap.caseInsensitiveMultiMap();
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

        assertNoCountersIncremented(context);
    }

    @Test
    public void testGETAnyResourceAfterFixingBrokenRouterShouldWorkAgain(TestContext context) {
        Router router = routerBuilder().build();
        context.assertTrue(router.isRoutingBroken(), "Routing should be broken because of missing properties entry");
        context.assertNotNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");
        context.assertTrue(router.getRoutingBrokenMessage().contains("gateleen.test.prop.1"), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");

        // get random resource with broken routing. Should not work
        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        class GETRandomResourceRequest extends DummyHttpServerRequest {
            @Override
            public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override
            public String uri() {
                return "/gateleen/server/random/resource";
            }

            @Override
            public String path() {
                return "/gateleen/server/random/resource";
            }

            @Override
            public MultiMap headers() {
                return MultiMap.caseInsensitiveMultiMap();
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
        class UpdateRulesWithValidResourceRequest extends DummyHttpServerRequest {
            @Override
            public HttpMethod method() {
                return HttpMethod.PUT;
            }

            @Override
            public String uri() {
                return "/gateleen/server/admin/v1/routing/rules";
            }

            @Override
            public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
                bodyHandler.handle(Buffer.buffer(RULES_WITH_VALID_PROPS));
                return this;
            }

            @Override
            public MultiMap headers() {
                return MultiMap.caseInsensitiveMultiMap();
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
            @Override
            public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override
            public String uri() {
                return "/gateleen/server/random/resource";
            }

            @Override
            public String path() {
                return "/gateleen/server/random/resource";
            }

            @Override
            public MultiMap params() {
                return MultiMap.caseInsensitiveMultiMap();
            }

            @Override
            public MultiMap headers() {
                return MultiMap.caseInsensitiveMultiMap();
            }

            @Override
            public DummyHttpServerResponse response() {
                return responseRandomResource;
            }

            @Override
            public HttpServerRequest pause() {
                return this;
            }
        }
        GETRandomResourceAgainRequest requestRandomResource = new GETRandomResourceAgainRequest();
        router.route(requestRandomResource);
        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken anymore");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");
    }

    @Test
    public void testServerInfoRequestsAreAvailableWithDefaultRoutes(TestContext context) {
        storage = new MockResourceStorage(ImmutableMap.of(rulesPath, RULES_WITH_HOPS));
        long ts = System.currentTimeMillis();
        JsonObject info = new JsonObject().put("ts", ts);
        Router router = routerBuilder().withInfo(info).withStorage(storage).build();

        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        response.setStatusCode(StatusCode.OK.getStatusCode());
        response.setStatusMessage(StatusCode.OK.getStatusMessage());

        class GETServerInfoRequest extends DummyHttpServerRequest {
            @Override
            public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override
            public String uri() {
                return "/gateleen/server/info";
            }

            @Override
            public String path() {
                return "/gateleen/server/info";
            }

            @Override
            public MultiMap params() {
                return MultiMap.caseInsensitiveMultiMap();
            }

            @Override
            public MultiMap headers() {
                return MultiMap.caseInsensitiveMultiMap();
            }

            @Override
            public DummyHttpServerResponse response() {
                return response;
            }
        }

        GETServerInfoRequest request = new GETServerInfoRequest();
        router.route(request);

        context.assertEquals(StatusCode.OK.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 200");
        context.assertEquals(StatusCode.OK.getStatusMessage(), request.response().getStatusMessage(), "StatusMessage should be OK");
        context.assertEquals(ts, new JsonObject(request.response().getResultBuffer()).getLong("ts"));

        assertNoCountersIncremented(context);
    }

    @Test
    public void testServerInfoRequestsAreNotAvailableWhenDefaultRoutesAreOverridden(TestContext context) {
        Set<Router.DefaultRouteType> defaultRouteTypes = new HashSet() {{
            add(Router.DefaultRouteType.SIMULATOR);
            add(Router.DefaultRouteType.DEBUG);
        }};

        storage = new MockResourceStorage(ImmutableMap.of(rulesPath, RULES_WITH_HOPS));
        Router router = routerBuilder()
                .withDefaultRouteTypes(defaultRouteTypes)
                .withStorage(storage)
                .build();

        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        response.setStatusCode(StatusCode.OK.getStatusCode());
        response.setStatusMessage(StatusCode.OK.getStatusMessage());

        class GETServerInfoRequest extends DummyHttpServerRequest {
            @Override
            public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override
            public String uri() {
                return "/gateleen/server/info";
            }

            @Override
            public String path() {
                return "/gateleen/server/info";
            }

            @Override
            public MultiMap params() {
                return MultiMap.caseInsensitiveMultiMap();
            }

            @Override
            public MultiMap headers() {
                return MultiMap.caseInsensitiveMultiMap();
            }

            @Override
            public DummyHttpServerResponse response() {
                return response;
            }
        }

        GETServerInfoRequest request = new GETServerInfoRequest();
        router.route(request);

        context.assertEquals(StatusCode.NOT_FOUND.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 404");
        assertNoCountersIncremented(context);
    }

    @Test
    public void testStorageRequestWithHeadersFilterPresent(TestContext context) {
        storage = new MockResourceStorage(ImmutableMap.of(rulesPath, RULES_WITH_HEADERSFILTER, serverUrl + "forward/to/storage", RANDOM_RESOURCE));
        Router router = routerBuilder().withStorage(storage).build();
        ConfigurationResourceManager configurationResourceManager = Mockito.mock(ConfigurationResourceManager.class);
        router.enableRoutingConfiguration(configurationResourceManager, serverUrl + "/admin/v1/routing/config");

        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");

        final DummyHttpServerResponse response = new DummyHttpServerResponse() {
            @Override
            public Future<Void> write(Buffer data) {
                return Future.succeededFuture();
            }
        };
        response.setStatusCode(StatusCode.OK.getStatusCode());
        response.setStatusMessage(StatusCode.OK.getStatusMessage());

        MultiMap headers = new HeadersMultiMap();
        headers.set("x-foo", "bar");
        DummyHttpServerRequest request = buildRequest(HttpMethod.GET, "/gateleen/server/forward/to/storage", headers, Buffer.buffer(RANDOM_RESOURCE), response);
        router.route(request);

        context.assertEquals(StatusCode.OK.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 200");
        context.assertEquals(StatusCode.OK.getStatusMessage(), request.response().getStatusMessage(), "StatusMessage should be OK");

        Timer timer = meterRegistry.get(AbstractForwarder.FORWARDS_METRIC_NAME).tag(AbstractForwarder.FORWARDER_METRIC_TAG_METRICNAME, "forward_storage").timer();
        context.assertEquals(1L, timer.count(), "Timer for `forward_storage` rule should have been incremented by 1");
    }

    @Test
    public void testStorageRequestWithHeadersFilterAbsent(TestContext context) {
        storage = new MockResourceStorage(ImmutableMap.of(rulesPath, RULES_WITH_HEADERSFILTER, serverUrl + "forward/to/storage", RANDOM_RESOURCE));
        Router router = routerBuilder().withStorage(storage).build();
        ConfigurationResourceManager configurationResourceManager = Mockito.mock(ConfigurationResourceManager.class);
        router.enableRoutingConfiguration(configurationResourceManager, serverUrl + "/admin/v1/routing/config");

        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");

        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        response.setStatusCode(StatusCode.OK.getStatusCode());
        response.setStatusMessage(StatusCode.OK.getStatusMessage());

        MultiMap headers = new HeadersMultiMap();
        DummyHttpServerRequest request = buildRequest(HttpMethod.GET, "/gateleen/server/forward/to/storage", headers, Buffer.buffer(RANDOM_RESOURCE), response);
        router.route(request);

        context.assertEquals(StatusCode.NOT_FOUND.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 404");

        assertNoCountersIncremented(context);
    }

    @Test
    public void testNullForwarderRequestWithHeadersFilterNotMatching(TestContext context) {
        storage = new MockResourceStorage(ImmutableMap.of(rulesPath, RULES_WITH_HEADERSFILTER, serverUrl + "forward/to/nowhere", RANDOM_RESOURCE));
        Router router = routerBuilder().withStorage(storage).build();
        ConfigurationResourceManager configurationResourceManager = Mockito.mock(ConfigurationResourceManager.class);
        router.enableRoutingConfiguration(configurationResourceManager, serverUrl + "/admin/v1/routing/config");

        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");

        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        response.setStatusCode(StatusCode.OK.getStatusCode());
        response.setStatusMessage(StatusCode.OK.getStatusMessage());

        MultiMap headers = new HeadersMultiMap();
        headers.set("x-foo", "99");
        DummyHttpServerRequest request = buildRequest(HttpMethod.PUT, "/gateleen/server/forward/to/nowhere", headers, Buffer.buffer(RANDOM_RESOURCE), response);
        router.route(request);

        context.assertEquals(StatusCode.NOT_FOUND.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 404");

        assertNoCountersIncremented(context);
    }

    @Test
    public void testNullForwarderRequestWithHeadersFilterPresent(TestContext context) {
        storage = new MockResourceStorage(ImmutableMap.of(rulesPath, RULES_WITH_HEADERSFILTER, serverUrl + "forward/to/nowhere", RANDOM_RESOURCE));
        Router router = routerBuilder().withStorage(storage).build();

        ConfigurationResourceManager configurationResourceManager = Mockito.mock(ConfigurationResourceManager.class);
        router.enableRoutingConfiguration(configurationResourceManager, serverUrl + "/admin/v1/routing/config");

        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");

        final DummyHttpServerResponse response = new DummyHttpServerResponse() {
            @Override
            public Future<Void> write(Buffer data) {
                return Future.succeededFuture();
            }
        };
        response.setStatusCode(StatusCode.OK.getStatusCode());
        response.setStatusMessage(StatusCode.OK.getStatusMessage());

        MultiMap headers = new HeadersMultiMap();
        headers.set("x-foo", "999");
        DummyHttpServerRequest request = buildRequest(HttpMethod.PUT, "/gateleen/server/forward/to/nowhere", headers, Buffer.buffer(RANDOM_RESOURCE), response);
        router.route(request);

        context.assertEquals(StatusCode.OK.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 200");
        context.assertEquals(StatusCode.OK.getStatusMessage(), request.response().getStatusMessage(), "StatusMessage should be OK");

        Timer timer = meterRegistry.get(AbstractForwarder.FORWARDS_METRIC_NAME).tag(AbstractForwarder.FORWARDER_METRIC_TAG_METRICNAME, "forward_null").timer();
        context.assertEquals(1L, timer.count(), "Timer for `forward_null` rule should have been incremented by 1");
    }

    @Test
    public void testForwarderRequestWithHeadersFilterPresent(TestContext context) {
        storage = new MockResourceStorage(ImmutableMap.of(rulesPath, RULES_WITH_HEADERSFILTER, serverUrl + "forward/to/backend", RANDOM_RESOURCE));
        Router router = routerBuilder().withStorage(storage).build();

        ConfigurationResourceManager configurationResourceManager = Mockito.mock(ConfigurationResourceManager.class);
        router.enableRoutingConfiguration(configurationResourceManager, serverUrl + "/admin/v1/routing/config");

        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");

        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        response.setStatusCode(StatusCode.OK.getStatusCode());
        response.setStatusMessage(StatusCode.OK.getStatusMessage());

        MultiMap headers = new HeadersMultiMap();
        headers.set("x-foo", "A");
        DummyHttpServerRequest request = buildRequest(HttpMethod.GET, "/gateleen/server/forward/to/backend", headers, Buffer.buffer(RANDOM_RESOURCE), response);
        router.route(request);

        // we expect a status code 500 because of a NullPointerException in the test setup
        // however, this means that the headersFilter evaluation did not return a 400 Bad Request
        context.assertEquals(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 500");
    }

    @Test
    public void testForwarderRequestWithHeadersFilterNotMatching(TestContext context) {
        storage = new MockResourceStorage(ImmutableMap.of(rulesPath, RULES_WITH_HEADERSFILTER, serverUrl + "forward/to/backend", RANDOM_RESOURCE));
        Router router = routerBuilder().withStorage(storage).build();

        ConfigurationResourceManager configurationResourceManager = Mockito.mock(ConfigurationResourceManager.class);
        router.enableRoutingConfiguration(configurationResourceManager, serverUrl + "/admin/v1/routing/config");

        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");

        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        response.setStatusCode(StatusCode.OK.getStatusCode());
        response.setStatusMessage(StatusCode.OK.getStatusMessage());

        MultiMap headers = new HeadersMultiMap();
        headers.set("x-foo", "X");
        DummyHttpServerRequest request = buildRequest(HttpMethod.GET, "/gateleen/server/forward/to/backend", headers, Buffer.buffer(RANDOM_RESOURCE), response);
        router.route(request);

        context.assertEquals(StatusCode.NOT_FOUND.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 404");

        assertNoCountersIncremented(context);
    }

    private DummyHttpServerRequest buildRequest(HttpMethod method, String uri, MultiMap headers, Buffer body, DummyHttpServerResponse response) {
        return new DummyHttpServerRequest() {
            @Override
            public HttpMethod method() {
                return method;
            }

            @Override
            public String uri() {
                return uri;
            }

            @Override
            public String path() {
                return uri;
            }

            @Override
            public MultiMap headers() {
                return headers;
            }

            @Override
            public MultiMap params() {
                return new HeadersMultiMap();
            }

            @Override
            public HttpServerRequest pause() {
                return this;
            }

            @Override
            public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
                bodyHandler.handle(body);
                return this;
            }

            @Override
            public HttpServerRequest handler(Handler<Buffer> handler) {
                handler.handle(body);
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
        };
    }
}
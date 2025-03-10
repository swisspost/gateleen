package org.swisspush.gateleen.hook;

import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.buffer.impl.BufferImpl;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.HostAndPort;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.RoutingContext;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.*;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.hook.reducedpropagation.ReducedPropagationManager;
import org.swisspush.gateleen.logging.LogAppenderRepository;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.core.util.ExpiryCheckHandler;
import org.swisspush.gateleen.queue.queuing.RequestQueue;
import org.swisspush.gateleen.routing.Router;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static io.vertx.core.http.HttpMethod.PUT;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.swisspush.gateleen.core.util.HttpRequestHeader.*;

/**
 * Tests for the {@link HookHandler} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class HookHandlerTest {
    private static final String HOOK_ROOT_URI = "hookRootURI/";
    private static final String HOOK_LISTENER_URI = "/"+ HOOK_ROOT_URI + "registrations/listeners";
    String HOOK_ROUTE_URI = "/"+ HOOK_ROOT_URI + "registrations/routes";
    private static final Logger logger = LoggerFactory.getLogger(HookHandlerTest.class);
    private Vertx vertx;
    private HttpClient httpClient;
    private MockResourceStorage storage;
    private LoggingResourceManager loggingResourceManager;
    private LogAppenderRepository logAppenderRepository;
    private MonitoringHandler monitoringHandler;
    private RequestQueue requestQueue;
    private ReducedPropagationManager reducedPropagationManager;

    private HookHandler hookHandler;

    private RoutingContext routingContext;
    private HttpServerResponse mockResponse;

    @Before
    public void setUp() {
        vertx = Vertx.vertx();
        routingContext = mock(RoutingContext.class);
        httpClient = mock(HttpClient.class);
        when(httpClient.request(any(HttpMethod.class), anyString())).thenReturn(mock(Future.class));
        storage = new MockResourceStorage();
        loggingResourceManager = mock(LoggingResourceManager.class);
        logAppenderRepository = mock(LogAppenderRepository.class);
        monitoringHandler = mock(MonitoringHandler.class);
        requestQueue = mock(RequestQueue.class);
        reducedPropagationManager = mock(ReducedPropagationManager.class);
        mockResponse = mock(HttpServerResponse.class);
        hookHandler = new HookHandler(vertx, httpClient, storage, loggingResourceManager, logAppenderRepository,
                monitoringHandler, "userProfilePath", HOOK_ROOT_URI, requestQueue, false, reducedPropagationManager);
        hookHandler.init();
    }

    private void setListenerStorageEntryAndTriggerUpdate(JsonObject listenerConfig) {
        storage.putMockData("pathToListenerResource", listenerConfig.encode());
        vertx.eventBus().request("gateleen.hook-listener-insert", "pathToListenerResource");
    }

    private JsonObject buildListenerConfig(JsonObject queueingStrategy, String deviceId) {
        JsonObject config = new JsonObject();
        config.put("requesturl", "/playground/server/tests/hooktest/_hooks/listeners/http/push/" + deviceId);
        config.put("expirationTime", "2017-01-03T14:15:53.277");

        JsonObject hook = new JsonObject();
        hook.put("destination", "/playground/server/push/v1/devices/" + deviceId);
        hook.put("methods", new JsonArray(Collections.singletonList("PUT")));
        hook.put("expireAfter", 300);
        hook.put("fullUrl", true);
        JsonObject staticHeaders = new JsonObject();
        staticHeaders.put("x-sync", true);
        hook.put("staticHeaders", staticHeaders);

        if (queueingStrategy != null) {
            hook.put("queueingStrategy", queueingStrategy);
        }

        config.put("hook", hook);
        return config;
    }

    private JsonObject buildRouteConfig(String routeId) {
        JsonObject config = new JsonObject();
        config.put("requesturl", "/playground/server/tests/"+ routeId+"/_hooks/routes/http/push/" );
        config.put("expirationTime", "2017-01-03T14:15:53.277");

        JsonObject hook = new JsonObject();
        hook.put("destination", "/playground/server/push/v1/routes/" + routeId);
        hook.put("methods", new JsonArray(Collections.singletonList("PUT")));
        hook.put("timeout", 42);
        hook.put("connectionPoolSize", 10);

        JsonObject staticHeaders = new JsonObject();
        staticHeaders.put("x-custom-header", "route-header-value");
        hook.put("staticHeaders", staticHeaders);
        config.put("hook", hook);
        return config;
    }
    private void setRouteStorageEntryAndTriggerUpdate(JsonObject routeConfig) {
        storage.putMockData("pathToRouteResource", routeConfig.encode());
        vertx.eventBus().request("gateleen.hook-route-insert", "pathToRouteResource");
    }

    private JsonObject buildListenerConfigWithHeadersFilter(JsonObject queueingStrategy, String deviceId, String headersFilter) {
        JsonObject config = buildListenerConfig(queueingStrategy, deviceId);
        config.getJsonObject("hook").put("headersFilter", headersFilter);
        return config;
    }

    @Test
    public void testListenerEnqueueWithDefaultQueueingStrategy(TestContext context) throws InterruptedException {

        // trigger listener update via event bus
        setListenerStorageEntryAndTriggerUpdate(buildListenerConfig(null, "x99")); // listenerConfig without 'queueingStrategy' configuration

        // wait a moment to let the listener be registered
        Thread.sleep(1000);

        // make a change to the hooked resource
        String uri = "/playground/server/tests/hooktest/abc123";
        String originalPayload = "{\"key\":123}";
        PUTRequest putRequest = new PUTRequest(uri, originalPayload);
        putRequest.addHeader(CONTENT_LENGTH.getName(), "99");

        when(routingContext.request()).thenReturn(putRequest);

        hookHandler.handle(routingContext);

        // verify that enqueue has been called WITH the payload
        Mockito.verify(requestQueue, Mockito.timeout(2000).times(1)).enqueue(Mockito.argThat(req -> {
            return HttpMethod.PUT == req.getMethod()
                    && req.getUri().contains(uri)
                    && Integer.valueOf(99).equals(getInteger(req.getHeaders(), CONTENT_LENGTH)) // Content-Length header should not have changed
                    && Arrays.equals(req.getPayload(), Buffer.buffer(originalPayload).getBytes()); // payload should not have changed
        }), anyString(), any(Handler.class));
    }

    @Test
    public void testListenerEnqueueWithDefaultQueueingStrategyBecauseOfInvalidConfiguration(TestContext context) throws InterruptedException {
        // trigger listener update via event bus
        setListenerStorageEntryAndTriggerUpdate(buildListenerConfig(new JsonObject().put("type", "discardPayloadXXX"), "x99")); // invalid 'queueingStrategy' configuration results in a DefaultQueueingStrategy

        // wait a moment to let the listener be registered
        Thread.sleep(1000);

        // make a change to the hooked resource
        String uri = "/playground/server/tests/hooktest/abc123";
        String originalPayload = "{\"key\":123}";
        PUTRequest putRequest = new PUTRequest(uri, originalPayload);
        putRequest.addHeader(CONTENT_LENGTH.getName(), "99");

        when(routingContext.request()).thenReturn(putRequest);
        hookHandler.handle(routingContext);

        // verify that enqueue has been called WITH the payload
        Mockito.verify(requestQueue, Mockito.timeout(2000).times(1)).enqueue(Mockito.argThat(req -> {
            return HttpMethod.PUT == req.getMethod()
                    && req.getUri().contains(uri)
                    && Integer.valueOf(99).equals(getInteger(req.getHeaders(), CONTENT_LENGTH)) // Content-Length header should not have changed
                    && Arrays.equals(req.getPayload(), Buffer.buffer(originalPayload).getBytes()); // payload should not have changed
        }), anyString(), any(Handler.class));
    }

    @Test
    public void testListenerEnqueueWithDiscardPayloadQueueingStrategy(TestContext context) throws InterruptedException {
        // trigger listener update via event bus
        setListenerStorageEntryAndTriggerUpdate(buildListenerConfig(new JsonObject().put("type", "discardPayload"), "x99"));

        // wait a moment to let the listener be registered
        Thread.sleep(1000);

        // make a change to the hooked resource
        String uri = "/playground/server/tests/hooktest/abc123";
        String originalPayload = "{\"key\":123}";
        PUTRequest putRequest = new PUTRequest(uri, originalPayload);
        putRequest.addHeader(CONTENT_LENGTH.getName(), "99");
        when(routingContext.request()).thenReturn(putRequest);
        hookHandler.handle(routingContext);

        // verify that enqueue has been called WITHOUT the payload but with 'Content-Length : 0' header
        Mockito.verify(requestQueue, Mockito.timeout(2000).times(1)).enqueue(Mockito.argThat(req -> {
            return HttpMethod.PUT == req.getMethod()
                    && req.getUri().contains(uri)
                    && Integer.valueOf(0).equals(getInteger(req.getHeaders(), CONTENT_LENGTH))
                    && Arrays.equals(req.getPayload(), new byte[0]); // should not be original payload anymore
        }), anyString(), any(Handler.class));

        PUTRequest putRequestWithoutContentLengthHeader = new PUTRequest(uri, originalPayload);
        when(routingContext.request()).thenReturn(putRequestWithoutContentLengthHeader);
        hookHandler.handle(routingContext);

        // verify that enqueue has been called WITHOUT the payload and WITHOUT 'Content-Length' header
        Mockito.verify(requestQueue, Mockito.timeout(2000).times(1)).enqueue(Mockito.argThat(req -> {
            return HttpMethod.PUT == req.getMethod()
                    && req.getUri().contains(uri)
                    && !containsHeader(req.getHeaders(), CONTENT_LENGTH)
                    && Arrays.equals(req.getPayload(), new byte[0]); // should not be original payload anymore
        }), anyString(), any(Handler.class));
    }

    @Test
    public void testListenerEnqueueWithReducedPropagationQueueingStrategyButNoManager(TestContext context) throws InterruptedException {
        hookHandler = new HookHandler(vertx, httpClient, storage, loggingResourceManager, logAppenderRepository, monitoringHandler,
                "userProfilePath", HOOK_ROOT_URI, requestQueue, false, null);
        hookHandler.init();

        // trigger listener update via event bus
        setListenerStorageEntryAndTriggerUpdate(buildListenerConfig(new JsonObject().put("type", "reducedPropagation").put("intervalMs", 22), "x99"));

        // wait a moment to let the listener be registered
        Thread.sleep(1500);

        // make a change to the hooked resource
        String uri = "/playground/server/tests/hooktest/abc123";
        String originalPayload = "{\"key\":123}";
        PUTRequest putRequest = new PUTRequest(uri, originalPayload);
        putRequest.addHeader(CONTENT_LENGTH.getName(), "99");
        when(routingContext.request()).thenReturn(putRequest);
        hookHandler.handle(routingContext);

        // verify that no enqueue (or lockedEnqueue) has been called because no ReducedPropagationManager was configured
        Mockito.verifyNoInteractions(requestQueue);
        Mockito.verifyNoInteractions(reducedPropagationManager);
    }

    @Test
    public void testListenerEnqueueWithReducedPropagationQueueingStrategy(TestContext context) throws InterruptedException {
        String deviceId = "x99";
        long interval = 22;
        String queue = "listener-hook-http+push+" + deviceId + "+playground+server+tests+hooktest";

        // trigger listener update via event bus
        setListenerStorageEntryAndTriggerUpdate(buildListenerConfig(new JsonObject().put("type", "reducedPropagation").put("intervalMs", interval), deviceId));

        // wait a moment to let the listener be registered
        Thread.sleep(1500);

        // make a change to the hooked resource
        String uri = "/playground/server/tests/hooktest/abc123";
        String originalPayload = "{\"key\":123}";
        PUTRequest putRequest = new PUTRequest(uri, originalPayload);
        putRequest.addHeader(CONTENT_LENGTH.getName(), "99");

        when(routingContext.request()).thenReturn(putRequest);
        hookHandler.handle(routingContext);

        String targetUri = "/playground/server/push/v1/devices/" + deviceId + "/playground/server/tests/hooktest/abc123";
        Mockito.verify(reducedPropagationManager, Mockito.timeout(2000).times(1))
                .processIncomingRequest(eq(HttpMethod.PUT), eq(targetUri), any(MultiMap.class), eq(Buffer.buffer(originalPayload)), eq(queue), eq(interval), any(Handler.class));
    }

    @Test
    public void testListenerEnqueueWithInvalidReducedPropagationQueueingStrategy(TestContext context) throws InterruptedException {
        // trigger listener update via event bus
        setListenerStorageEntryAndTriggerUpdate(buildListenerConfig(new JsonObject().put("type", "reducedPropagation").put("intervalMs", "not_a_number"), "x99")); // invalid 'queueingStrategy' configuration results in a DefaultQueueingStrategy

        // wait a moment to let the listener be registered
        Thread.sleep(1000);

        // make a change to the hooked resource
        String uri = "/playground/server/tests/hooktest/abc123";
        String originalPayload = "{\"key\":123}";
        PUTRequest putRequest = new PUTRequest(uri, originalPayload);
        putRequest.addHeader(CONTENT_LENGTH.getName(), "99");

        when(routingContext.request()).thenReturn(putRequest);
        hookHandler.handle(routingContext);

        // verify that enqueue has been called WITH the payload
        Mockito.verify(requestQueue, Mockito.timeout(2000).times(1)).enqueue(Mockito.argThat(req -> {
            return HttpMethod.PUT == req.getMethod()
                    && req.getUri().contains(uri)
                    && Integer.valueOf(99).equals(getInteger(req.getHeaders(), CONTENT_LENGTH)) // Content-Length header should not have changed
                    && Arrays.equals(req.getPayload(), Buffer.buffer(originalPayload).getBytes()); // payload should not have changed
        }), anyString(), any(Handler.class));
    }

    @Test
    public void testListenerEnqueueWithMatchingRequestsHeaderFilter(TestContext context) throws InterruptedException {
        // trigger listener update via event bus
        setListenerStorageEntryAndTriggerUpdate(buildListenerConfigWithHeadersFilter(null, "x99", "x-foo: (A|B)"));

        // wait a moment to let the listener be registered
        Thread.sleep(1000);

        // make a change to the hooked resource
        String uri = "/playground/server/tests/hooktest/abc123";
        String originalPayload = "{\"key\":123}";
        PUTRequest putRequest = new PUTRequest(uri, originalPayload);
        putRequest.addHeader(CONTENT_LENGTH.getName(), "99");
        putRequest.addHeader("x-foo", "A");

        when(routingContext.request()).thenReturn(putRequest);
        hookHandler.handle(routingContext);

        // verify that enqueue has been called WITH the payload
        Mockito.verify(requestQueue, Mockito.timeout(2000).times(1)).enqueue(Mockito.argThat(req -> {
            return HttpMethod.PUT == req.getMethod()
                    && req.getUri().contains(uri)
                    && Integer.valueOf(99).equals(getInteger(req.getHeaders(), CONTENT_LENGTH)) // Content-Length header should not have changed
                    && Arrays.equals(req.getPayload(), Buffer.buffer(originalPayload).getBytes()); // payload should not have changed
        }), anyString(), any(Handler.class));
    }

    @Test
    public void testListenerNoEnqueueWithoutMatchingRequestsHeaderFilter(TestContext context) throws InterruptedException {
        // trigger listener update via event bus
        setListenerStorageEntryAndTriggerUpdate(buildListenerConfigWithHeadersFilter(null, "x99", "x-foo: (A|B)"));

        // wait a moment to let the listener be registered
        Thread.sleep(1000);

        // make a change to the hooked resource
        String uri = "/playground/server/tests/hooktest/abc123";
        String originalPayload = "{\"key\":123}";
        PUTRequest putRequest = new PUTRequest(uri, originalPayload);
        putRequest.addHeader(CONTENT_LENGTH.getName(), "99");
        when(routingContext.request()).thenReturn(putRequest);
        hookHandler.handle(routingContext);

        // verify that no enqueue has been called since the header did not match
        Mockito.verifyNoInteractions(requestQueue);
    }

    @Test
    public void testInvalidJsonInListener(TestContext context) throws InterruptedException {
        String uri = "/playground/server/tests/_hooks/listeners/test";
        assert400(uri, "{\"key\":123}");
        assert400(uri, "{");
    }

    @Test
    public void testInvalidJsonInRoute(TestContext context) throws InterruptedException {
        String uri = "/playground/server/tests/_hooks/route";
        assert400(uri, "{\"key\":123}");
        assert400(uri, "{");
    }


    ///////////////////////////////////////////////////////////////////////////////
    // Tests for hook registration
    ///////////////////////////////////////////////////////////////////////////////

    @Test
    public void hookRegistration_usesDefaultExpiryIfExpireAfterHeaderIsNegativeNumber(TestContext testContext) {
        // Initialize mock
        final int[] statusCodePtr = new int[]{0};
        final String[] statusMessagePtr = new String[]{null};
        final HttpServerRequest request;
        {  // Mock request
            final MultiMap requestHeaders = MultiMap.caseInsensitiveMultiMap();
            // Do NOT set to -1. Because that would be a valid value representing 'infinite'.
            requestHeaders.set(ExpiryCheckHandler.EXPIRE_AFTER_HEADER, "-42");
            final Buffer requestBody = createMinimalHookBodyAsBuffer();
            request = createSimpleRequest(HttpMethod.PUT, "/gateleen/example/_hooks/listeners/http/my-service/my-hook",
                    requestHeaders, requestBody, statusCodePtr, statusMessagePtr
            );
        }

        // Trigger work
        when(routingContext.request()).thenReturn(request);
        hookHandler.handle(routingContext);

        // Assert request was ok
        testContext.assertEquals(200, statusCodePtr[0]);

        { // Assert expiration time has same length as a valid date (including time zone)
            final String storedHook = storage.getMockData().get(HOOK_ROOT_URI + "registrations/listeners/http+my-service+my-hook+gateleen+example");
            testContext.assertNotNull(storedHook);
            final String expirationTime = new JsonObject(storedHook).getString("expirationTime");
            testContext.assertNotNull(expirationTime);
            testContext.assertEquals("____-__-__T__:__:__.___+__:__".length(), expirationTime.length());
        }
    }

    @Test
    public void hookRegistration_RouteWithTimeout(TestContext testContext) {
        // Initialize mock
        final int[] statusCodePtr = new int[]{0};
        final String[] statusMessagePtr = new String[]{null};
        final HttpServerRequest request;
        {  // Mock request
            final MultiMap requestHeaders = MultiMap.caseInsensitiveMultiMap();
            final Buffer requestBody = new BufferImpl();
            requestBody.setBytes(0, ("{" +
                    "    \"methods\": [ \"PUT\" , \"DELETE\" ]," +
                    "    \"destination\": \"/an/example/destination/\"," +
                    "    \"timeout\": 42" +
                    "}").getBytes());

            request = createSimpleRequest(HttpMethod.PUT, "/gateleen/example/_hooks/route/http/my-service/my-hook",
                    requestHeaders, requestBody, statusCodePtr, statusMessagePtr
            );
        }

        // Trigger work
        when(routingContext.request()).thenReturn(request);
        hookHandler.handle(routingContext);

        // Assert request was ok
        testContext.assertEquals(200, statusCodePtr[0]);

        { // Assert expiration time has same length as a valid date (including time zone)
            final String storedHook = storage.getMockData().get(HOOK_ROOT_URI + "registrations/routes/+gateleen+example+_hooks+route+http+my-service+my-hook");
            testContext.assertNotNull(storedHook);
            final Integer timeout = new JsonObject(storedHook).getJsonObject("hook").getInteger("timeout");
            testContext.assertNotNull(timeout);
            testContext.assertEquals(42, timeout);
        }
    }

    @Test
    public void hookRegistration_usesDefaultExpiryWhenHeaderContainsCorruptValue(TestContext testContext) {
        // In my opinion gateleen should answer with 400 in this case. But that's another topic.

        // Initialize mock
        /* Reference to retrieve statusCode */
        final int[] statusCodePtr = new int[]{0};
        /* Reference to retrieve statusMessage */
        final String[] statusMessagePtr = new String[]{null};
        final HttpServerRequest request;
        {  // Mock request
            final MultiMap requestHeaders = MultiMap.caseInsensitiveMultiMap();
            requestHeaders.set(ExpiryCheckHandler.EXPIRE_AFTER_HEADER, "This is definitively not a number :)");
            final Buffer requestBody = createMinimalHookBodyAsBuffer();
            request = createSimpleRequest(HttpMethod.PUT, "/gateleen/example/_hooks/listeners/http/my-service/my-fancy-hook",
                    requestHeaders, requestBody, statusCodePtr, statusMessagePtr
            );
        }

        // Trigger work
        when(routingContext.request()).thenReturn(request);
        hookHandler.handle(routingContext);

        // Assert request was ok
        testContext.assertEquals(200, statusCodePtr[0]);

        { // Assert expiration time has same length as a valid date (including time zone)
            final String storedHook = storage.getMockData().get(HOOK_ROOT_URI + "registrations/listeners/http+my-service+my-fancy-hook+gateleen+example");
            testContext.assertNotNull(storedHook);
            final String expirationTime = new JsonObject(storedHook).getString("expirationTime");
            testContext.assertNotNull(expirationTime);
            testContext.assertEquals("____-__-__T__:__:__.___+__:__".length(), expirationTime.length());
        }
    }

    @Test
    public void hookRegistration_usesDefaultExpiryIfHeaderIsMissing(TestContext testContext) {
        // Initialize mock
        final int[] statusCodePtr = new int[]{0};
        final String[] statusMessagePtr = new String[]{null};
        final HttpServerRequest request;
        {  // Mock request
            final MultiMap requestHeaders = MultiMap.caseInsensitiveMultiMap();
            // Do NOT set any header here.
            final Buffer requestBody = createMinimalHookBodyAsBuffer();
            request = createSimpleRequest(HttpMethod.PUT, "/gateleen/example/_hooks/listeners/http/my-service/yet-another-hook",
                    requestHeaders, requestBody, statusCodePtr, statusMessagePtr
            );
        }

        // Trigger work
        when(routingContext.request()).thenReturn(request);
        hookHandler.handle(routingContext);

        // Assert request was ok
        testContext.assertEquals(200, statusCodePtr[0]);

        { // Assert expiration time has same length as a valid date (including timezone)
            final String storedHook = storage.getMockData().get(HOOK_ROOT_URI + "registrations/listeners/http+my-service+yet-another-hook+gateleen+example");
            testContext.assertNotNull(storedHook);
            final String expirationTime = new JsonObject(storedHook).getString("expirationTime");
            testContext.assertNotNull(expirationTime);
            testContext.assertEquals("____-__-__T__:__:__.___+__:__".length(), expirationTime.length());
        }
    }

    @Test
    public void hookRegistration_usesMinusOneIfExpireAfterIsSetToMinusOne(TestContext testContext) {
        // Initialize mock
        final int[] statusCodePtr = new int[]{0};
        final String[] statusMessagePtr = new String[]{null};
        final HttpServerRequest request;
        {  // Mock request
            final MultiMap requestHeaders = MultiMap.caseInsensitiveMultiMap();
            requestHeaders.set(ExpiryCheckHandler.EXPIRE_AFTER_HEADER, "-1");
            final Buffer requestBody = createMinimalHookBodyAsBuffer();
            request = createSimpleRequest(HttpMethod.PUT, "/gateleen/example/_hooks/listeners/http/my-service/and-one-more-again-hook",
                    requestHeaders, requestBody, statusCodePtr, statusMessagePtr
            );
        }

        // Trigger work
        when(routingContext.request()).thenReturn(request);
        hookHandler.handle(routingContext);

        // Assert request was ok
        testContext.assertEquals(200, statusCodePtr[0]);

        { // Assert expiration time is set to -1 (infinite)
            final String storedHook = storage.getMockData().get(HOOK_ROOT_URI + "registrations/listeners/http+my-service+and-one-more-again-hook+gateleen+example");
            testContext.assertNotNull(storedHook);
            final String expirationTime = new JsonObject(storedHook).getString("expirationTime");
            logger.debug("expirationTime is '" + expirationTime + "'");
            testContext.assertNull(expirationTime);
        }
    }

    @Test
    public void listenerRegistration_acceptOnlyWhitelistedHttpMethods(TestContext testContext) {

        // Mock a request using all allowed request methods.
        final int[] statusCodePtr = new int[]{0};
        final String[] statusMessagePtr = new String[]{null};
        final HttpServerRequest request;
        {
            final Buffer requestBody = toBuffer(new JsonObject("{" +
                    "    \"methods\": [ \"OPTIONS\" , \"HEAD\" , \"GET\" , \"POST\" , \"PUT\" , \"DELETE\" , \"PATCH\" ]," +
                    "    \"destination\": \"/an/example/destination/\"" +
                    "}"
            ));
            request = createSimpleRequest(PUT, "/gateleen/example/_hooks/listeners/http/my-service/a-random-hook-8518ul4st8d6944r6k",
                    requestBody, statusCodePtr, statusMessagePtr
            );
        }

        // Trigger
        when(routingContext.request()).thenReturn(request);
        hookHandler.handle(routingContext);

        { // Assert request got accepted.
            testContext.assertEquals(200, statusCodePtr[0]);
        }
    }

    @Test
    public void listenerRegistration_rejectNotWhitelistedHttpMethods(TestContext testContext) {
        // Some valid HTTP methods gateleen not accepts.
        final List<String> badMethods = List.of("CONNECT", "TRACE",
                // Some methods available in postman gateleen doesn't care about.
                "COPY", "LINK", "UNLINK", "PURGE", "LOCK", "UNLOCK", "PROPFIND", "VIEW",
                // Some random, hopefully invalid methods.
                "FOO", "BAR", "ASDF", "ASSRGHAWERTH");

        // Test every method.
        for (String method : badMethods) {

            // Mock a request using current method.
            final int[] statusCodePtr = new int[]{0};
            final String[] statusMessagePtr = new String[]{null};
            final HttpServerRequest request;
            {
                final Buffer requestBody = toBuffer(new JsonObject("{" +
                        "    \"methods\": [ \"" + method + "\" ]," +
                        "    \"destination\": \"/an/example/destination/\"" +
                        "}"
                ));
                request = createSimpleRequest(PUT, "/gateleen/example/_hooks/listeners/http/my-service/a-random-hook-8518ul4st8d6944r6k",
                        requestBody, statusCodePtr, statusMessagePtr
                );
            }

            // Trigger
            when(routingContext.request()).thenReturn(request);
            hookHandler.handle(routingContext);

            { // Assert request got rejected.
                testContext.assertEquals(400, statusCodePtr[0]);
            }
        }
    }

    @Test
    public void hookRegistration_RouteWithRouteMultiplier(TestContext testContext) throws InterruptedException {
        // Initialize mock, expires in 2s
        String expirationTime = DateTime.now().plusSeconds(2).toString();
        storage.putMockData("pathToRouterResource", ("{\n" +
                "   \"requesturl\":\"/server/services/api/_hooks/route\",\n" +
                "   \"expirationTime\":\"" + expirationTime + "\",\n" +
                "   \"hook\":{\n" +
                "      \"destination\":\"http://localhost/aservice/v1/events\",\n" +
                "      \"methods\":[\n" +
                "         \"PUT\"\n" +
                "      ],\n" +
                "      \"connectionPoolSize\":10\n" +
                "   }\n" +
                "}"));
        vertx.eventBus().request("gateleen.hook-route-insert", "pathToRouterResource");
        // wait a moment to let the router be registered
        Thread.sleep(1000);

        testContext.assertEquals(1, hookHandler.routeRepository.getRoutes().values().size());
        { // Assert connectionPoolSize, should be unchanged : 10
            Route route = hookHandler.routeRepository.getRoutes().get(hookHandler.routeRepository.getRoutes().keySet().toArray()[0].toString());
            final Integer connectionPoolSize = route.getHook().getConnectionPoolSize();
            testContext.assertNotNull(connectionPoolSize);
            testContext.assertEquals(10, connectionPoolSize);
        }

        // Update multiplier
        vertx.eventBus().publish(Router.ROUTE_MULTIPLIER_ADDRESS, "2");
        Thread.sleep(2000);
        // Old on should be expired

        expirationTime = DateTime.now().plusSeconds(2).toString();
        storage.putMockData("pathToRouterResource", ("{\n" +
                "   \"requesturl\":\"/server/services/api/_hooks/route\",\n" +
                "   \"expirationTime\":\"" + expirationTime + "\",\n" +
                "   \"hook\":{\n" +
                "      \"destination\":\"http://localhost/aservice/v1/events\",\n" +
                "      \"methods\":[\n" +
                "         \"PUT\"\n" +
                "      ],\n" +
                "      \"connectionPoolSize\":10\n" +
                "   }\n" +
                "}"));
        vertx.eventBus().request("gateleen.hook-route-insert", "pathToRouterResource");
        // wait a moment to let the router be registered
        Thread.sleep(1000);

        testContext.assertEquals(1, hookHandler.routeRepository.getRoutes().values().size());
        { // Assert connectionPoolSize, should be changed : 5
            Route route = hookHandler.routeRepository.getRoutes().get(hookHandler.routeRepository.getRoutes().keySet().toArray()[0].toString());
            final Integer connectionPoolSize = route.getHook().getConnectionPoolSize();
            testContext.assertNotNull(connectionPoolSize);
            testContext.assertEquals(5, connectionPoolSize);
        }

        //clean up
        vertx.eventBus().request("gateleen.hook-route-remove", "pathToRouterResource");
    }


    @Test
    public void testHandleGETRequestWithEmptyParam(TestContext testContext) {
        // Define URI and configures the request with an empty 'q' parameter
        GETRequest request = new GETRequest(HOOK_LISTENER_URI, mockResponse);
        request.addParameter("q", ""); // Empty parameter to simulate bad request

        // Mock RoutingContext
        when(routingContext.request()).thenReturn(request);

        // Capture response content
        ArgumentCaptor<String> responseCaptor = ArgumentCaptor.forClass(String.class);
        when(mockResponse.setStatusCode(anyInt())).thenReturn(mockResponse);
        when(mockResponse.end(responseCaptor.capture())).thenReturn(Future.succeededFuture());

        // Execute the Handler
        boolean result = hookHandler.handle(routingContext);

        // Verify status 400 due to empty 'q' parameter
        verify(mockResponse).setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
        testContext.assertTrue(result);
        // Verify captured response content
        String jsonResponse = responseCaptor.getValue();
        testContext.assertNotNull(jsonResponse);
        // Confirm the response contains "Bad Request"
        testContext.assertTrue(jsonResponse.contains("Only the 'q' parameter is allowed and can't be empty or null"));
    }

    @Test
    public void testHandleGETRequestWithNoParam(TestContext testContext) {
        GETRequest request = new GETRequest(HOOK_LISTENER_URI, mockResponse);
        when(routingContext.request()).thenReturn(request);

        boolean result = hookHandler.handle(routingContext);

        testContext.assertFalse(result);
    }

    @Test
    public void testHandleGETRequestWithWrongRoute(TestContext testContext) {
        String wrongUri = "/hookRootURI/registrati/listeners";
        GETRequest request = new GETRequest(wrongUri, mockResponse);
        request.addParameter("q", "value");
        when(routingContext.request()).thenReturn(request);

        boolean result = hookHandler.handle(routingContext);

        testContext.assertFalse(result);
    }

    @Test
    public void testHandleGETRequestWithListenersSearchSingleResult() throws InterruptedException {
        // Define URI and configure GET request with specific search parameter
        String singleListener= "mySingleListener";
        GETRequest request = new GETRequest(HOOK_LISTENER_URI, mockResponse);
        request.addParameter("q", singleListener);

        setListenerStorageEntryAndTriggerUpdate(buildListenerConfigWithHeadersFilter(null, singleListener, "x-foo: (A|B)"));
        // wait a moment to let the listener be registered
        Thread.sleep(200);
        // Mock RoutingContext and configure response capture
        when(routingContext.request()).thenReturn(request);

        ArgumentCaptor<String> responseCaptor = ArgumentCaptor.forClass(String.class);
        when(mockResponse.setStatusCode(anyInt())).thenReturn(mockResponse);
        when(mockResponse.end(responseCaptor.capture())).thenReturn(Future.succeededFuture());

        // Execute the handler
        boolean result = hookHandler.handle(routingContext);
        assertTrue(result);

        // Validate JSON response content for matching listener
        String jsonResponse = responseCaptor.getValue();
        assertNotNull(jsonResponse);
        assertTrue(jsonResponse.contains(singleListener));
    }

    @Test
    public void testHandleGETRequestWithListenersSearchMultipleResults() throws InterruptedException {
        // Define the URI and set up the GET request with a broader search parameter for multiple listeners
        GETRequest request = new GETRequest(HOOK_LISTENER_URI, mockResponse);
        request.addParameter("q", "myListener"); // Search parameter that should match multiple listeners

        // Add multiple listeners to the MockResourceStorage using the expected configuration and register them
        String listenerId1 = "myListener112222";
        String listenerId2 = "myListener222133";
        String notMatchListener = "notMatchListener";
        setListenerStorageEntryAndTriggerUpdate(buildListenerConfigWithHeadersFilter(null, listenerId1, "x-foo: (A|B)"));
        Thread.sleep(200);
        setListenerStorageEntryAndTriggerUpdate(buildListenerConfigWithHeadersFilter(null, listenerId2, "x-foo: (A|B)"));
        Thread.sleep(200);
        setListenerStorageEntryAndTriggerUpdate(buildListenerConfigWithHeadersFilter(null, notMatchListener, "x-foo: (A|B)"));
        Thread.sleep(200);

        // Mock the RoutingContext and set up the response capture
        when(routingContext.request()).thenReturn(request);
        ArgumentCaptor<String> responseCaptor = ArgumentCaptor.forClass(String.class);
        when(mockResponse.setStatusCode(anyInt())).thenReturn(mockResponse);
        when(mockResponse.end(responseCaptor.capture())).thenReturn(Future.succeededFuture());

        // Execute the handler
        boolean result = hookHandler.handle(routingContext);
        assertTrue(result);

        // Validate the JSON response content for multiple matching listeners
        String jsonResponse = responseCaptor.getValue();
        assertNotNull(jsonResponse);
        assertTrue(jsonResponse.contains(listenerId1));
        assertTrue(jsonResponse.contains(listenerId2));
        assertFalse(jsonResponse.contains(notMatchListener));
    }

    @Test
    public void testHandleGETRequestWithRoutesSearchEmptyResult() {
        // Define URI and configure request with specific 'q' parameter for routes search
        GETRequest request = new GETRequest(HOOK_ROUTE_URI, mockResponse);
        request.addParameter("q", "routeNotFound");

        // No routes are added to MockResourceStorage to simulate empty result
        storage.putMockData(HOOK_ROUTE_URI, new JsonArray().encode());

        // Mock RoutingContext and configure response capture
        when(routingContext.request()).thenReturn(request);

        ArgumentCaptor<String> responseCaptor = ArgumentCaptor.forClass(String.class);
        when(mockResponse.setStatusCode(anyInt())).thenReturn(mockResponse);
        when(mockResponse.end(responseCaptor.capture())).thenReturn(Future.succeededFuture());

        // Execute the handler
        boolean result = hookHandler.handle(routingContext);

        // Verifications
        assertTrue(result);

        // Verify response content with empty result
        String actualResponse = responseCaptor.getValue();
        assertNotNull(actualResponse);
        JsonObject jsonResponse = new JsonObject(actualResponse);
        assertTrue("Expected 'routes' to be an empty array",
                jsonResponse.containsKey("routes") && jsonResponse.getJsonArray("routes").isEmpty());
    }

    @Test
    public void testHandleGETRequestWithRoutesSearchMultipleResults() throws InterruptedException {
        // Define the URI and set up the GET request with a broad search parameter for multiple routes
        GETRequest request = new GETRequest(HOOK_ROUTE_URI, mockResponse);
        request.addParameter("q", "valid"); // Search parameter that should match multiple routes

        // Add multiple routes to the MockResourceStorage using the expected configuration and register them
        String routeId1 = "valid12345";
        String routeId2 = "valid67890";
        String notPreset = "notPreset";
        setRouteStorageEntryAndTriggerUpdate(buildRouteConfig(routeId1));
        Thread.sleep(200);
        setRouteStorageEntryAndTriggerUpdate(buildRouteConfig( routeId2));
        Thread.sleep(200);
        setRouteStorageEntryAndTriggerUpdate(buildRouteConfig( notPreset));
        Thread.sleep(200);

        // Mock the RoutingContext and set up the response capture
        when(routingContext.request()).thenReturn(request);
        ArgumentCaptor<String> responseCaptor = ArgumentCaptor.forClass(String.class);
        when(mockResponse.setStatusCode(anyInt())).thenReturn(mockResponse);
        when(mockResponse.end(responseCaptor.capture())).thenReturn(Future.succeededFuture());

        // Execute the handler
        boolean result = hookHandler.handle(routingContext);
        assertTrue(result);

        // Validate the JSON response content for multiple matching routes
        String jsonResponse = responseCaptor.getValue();
        assertNotNull(jsonResponse);
        assertTrue(jsonResponse.contains(routeId1));
        assertTrue(jsonResponse.contains(routeId2));
        assertFalse(jsonResponse.contains(notPreset));
    }

    @Test
    public void testHandleListenerWithStorageAndEmptyList() {
        // Set up the URI for listeners registration
        GETRequest request = new GETRequest(HOOK_LISTENER_URI,mockResponse) ;
        request.addParameter("q", "validQueryParam");

        // Capture the response output
        ArgumentCaptor<String> responseCaptor = ArgumentCaptor.forClass(String.class);
        when(routingContext.request()).thenReturn(request);

        // Execute the handler and validate the response
        boolean result = hookHandler.handle(routingContext);

        assertTrue(result);
        verify(mockResponse).end(responseCaptor.capture());

        // Validate the response JSON for an empty listener list
        String actualResponse = responseCaptor.getValue();
        assertEmptyResult(actualResponse);
    }

    @Test
    public void testHandleGETRequestWithExtraParam(TestContext testContext) {
        // Define URI and configure the request with an extra parameter besides 'q'
        GETRequest request = new GETRequest(HOOK_LISTENER_URI, mockResponse);
        request.addParameter("q", "validQueryParam");
        request.addParameter("extra", "notAllowedParam"); // Extra parameter, not allowed

        // Mock the RoutingContext
        when(routingContext.request()).thenReturn(request);

        // Capture the response content
        ArgumentCaptor<String> responseCaptor = ArgumentCaptor.forClass(String.class);
        when(mockResponse.setStatusCode(anyInt())).thenReturn(mockResponse);
        when(mockResponse.end(responseCaptor.capture())).thenReturn(Future.succeededFuture());

        // Execute the Handler
        boolean result = hookHandler.handle(routingContext);

        // Verify status 400 due to the extra parameter
        verify(mockResponse).setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
        testContext.assertTrue(result);

        // Verify captured response content
        String jsonResponse = responseCaptor.getValue();
        testContext.assertNotNull(jsonResponse);
        // Confirm that the response contains "Bad Request"
        testContext.assertTrue(jsonResponse.contains("Only the 'q' parameter is allowed and can't be empty or null"));
    }

    @Test
    public void testHandleGETRequestWithTrailingSlash(TestContext testContext) {
        // Define URI with trailing slash and configure the request
        GETRequest request = new GETRequest(HOOK_LISTENER_URI + "/", mockResponse);
        request.addParameter("q", "validQueryParam");

        // Mock the RoutingContext
        when(routingContext.request()).thenReturn(request);

        // Capture the response content
        ArgumentCaptor<String> responseCaptor = ArgumentCaptor.forClass(String.class);
        when(mockResponse.end(responseCaptor.capture())).thenReturn(Future.succeededFuture());

        // Execute the Handler
        boolean result = hookHandler.handle(routingContext);

        // Verify the result contains an empty listeners list
        testContext.assertTrue(result);
        String jsonResponse = responseCaptor.getValue();
        assertEmptyResult(jsonResponse);
    }

    ///////////////////////////////////////////////////////////////////////////////
    // Helpers
    ///////////////////////////////////////////////////////////////////////////////

    private void assert400(final String uri, final String payload) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        DummyHttpServerResponse response = new DummyHttpServerResponse() {
            @Override
            public Future<Void> end(String chunk) {
                super.end(chunk);
                latch.countDown();
                return Future.succeededFuture();
            }
        };
        PUTRequest putRequest = new PUTRequest(uri, payload) {
            @Override
            public HttpServerResponse response() {
                return response;
            }
        };
        putRequest.addHeader(CONTENT_LENGTH.getName(), "99");

        when(routingContext.request()).thenReturn(putRequest);
        hookHandler.handle(routingContext);

        latch.await();
        assertEquals(400, response.getStatusCode());
    }

    private HttpServerRequest createSimpleRequest(final HttpMethod method, final String uri, final Buffer requestBody, final int[] statusCodePtr, final String[] statusMessagePtr) {
        return createSimpleRequest(method, uri, MultiMap.caseInsensitiveMultiMap(), requestBody, statusCodePtr, statusMessagePtr);
    }

    /**
     * Creates a simple {@link HttpServerRequest} mock.
     */
    private HttpServerRequest createSimpleRequest(final HttpMethod method, final String uri, final MultiMap requestHeaders, final Buffer requestBody, final int[] statusCodePtr, final String[] statusMessagePtr) {
        if (statusCodePtr == null) throw new IllegalArgumentException("Arg 'statusCodePtr' useless when null");
        if (statusMessagePtr == null) throw new IllegalArgumentException("Arg 'statusMessagePtr' useless when null");

        // Set implicit defaults
        statusCodePtr[0] = 200;
        statusMessagePtr[0] = "OK";

        final HttpServerResponse response = new FastFailHttpServerResponse() {
            @Override
            public HttpServerResponse setStatusCode(int statusCode) {
                statusCodePtr[0] = statusCode;
                return this;
            }

            @Override
            public HttpServerResponse setStatusMessage(String statusMessage) {
                statusMessagePtr[0] = statusMessage;
                return this;
            }

            @Override
            public Future<Void> write(String chunk, String enc) {
                return null;
            }

            @Override
            public void write(String chunk, String enc, Handler<AsyncResult<Void>> handler) {

            }

            @Override
            public Future<Void> write(String chunk) {
                return null;
            }

            @Override
            public void write(String chunk, Handler<AsyncResult<Void>> handler) {

            }

            @Override
            public Future<Void> writeEarlyHints(MultiMap headers) {
                return null;
            }

            @Override
            public void writeEarlyHints(MultiMap headers, Handler<AsyncResult<Void>> handler) {

            }

            @Override
            public Future<Void> end(String chunk) {/* ignore */
                return Future.succeededFuture();
            }

            @Override
            public Future<Void> end(String chunk, String enc) {
                return null;
            }

            @Override
            public Future<Void> end(Buffer chunk) {
                return null;
            }

            @Override
            public Future<Void> write(Buffer data) {
                return null;
            }

            @Override
            public void write(Buffer data, Handler<AsyncResult<Void>> handler) {

            }

            @Override
            public Future<Void> end() {/* ignore */
                return Future.succeededFuture();
            }

            @Override
            public Future<Void> sendFile(String filename, long offset, long length) {
                return null;
            }

            @Override
            public Future<HttpServerResponse> push(HttpMethod method, HostAndPort authority, String path, MultiMap headers) {
                return null;
            }

            @Override
            public Future<HttpServerResponse> push(HttpMethod method, String host, String path, MultiMap headers) {
                return null;
            }

            @Override
            public HttpServerResponse addCookie(Cookie cookie) {
                return null;
            }

            @Override
            public @Nullable Cookie removeCookie(String name, boolean invalidate) {
                return null;
            }

            @Override
            public Set<Cookie> removeCookies(String name, boolean invalidate) {
                return null;
            }

            @Override
            public @Nullable Cookie removeCookie(String name, String domain, String path, boolean invalidate) {
                return null;
            }
        };
        return new FastFailHttpServerRequest() {
            @Override
            public Context context() {
                return null;
            }

            @Override
            public Object metric() {
                return null;
            }

            @Override
            public HttpMethod method() {
                return method;
            }

            @Override
            public String uri() {
                return uri;
            }

            @Override
            public @Nullable HostAndPort authority() {
                return null;
            }

            @Override
            public MultiMap headers() {
                return requestHeaders;
            }

            @Override
            public HttpServerRequest setParamsCharset(String charset) {
                return null;
            }

            @Override
            public String getParamsCharset() {
                return null;
            }

            @Override
            public HttpServerRequest handler(Handler<Buffer> handler) {
                handler.handle(requestBody);
                return null;
            }

            @Override
            public HttpServerRequest endHandler(Handler<Void> endHandler) {
                endHandler.handle(null);
                return null;
            }

            @Override
            public HttpServerResponse response() {
                return response;
            }

            @Override
            public Future<Buffer> body() {
                return Future.succeededFuture(requestBody);
            }

        };
    }

    /**
     * @return A simple hook body as a JSON wrapped in a {@link Buffer}.
     */
    private Buffer createMinimalHookBodyAsBuffer() {
        final Buffer requestBody = new BufferImpl();
        requestBody.setBytes(0, ("{" +
                "    \"methods\": [ \"PUT\" , \"DELETE\" ]," +
                "    \"destination\": \"/an/example/destination/\"" +
                "}").getBytes());
        return requestBody;
    }

    private Buffer toBuffer(JsonObject jsonObject) {
        final Buffer buffer = new BufferImpl();
        buffer.setBytes(0, jsonObject.toString().getBytes());
        return buffer;
    }

    private static void assertEmptyResult(String actualResponse) {
        assertNotNull(actualResponse);
        JsonObject jsonResponse = new JsonObject(actualResponse);
        assertTrue("Expected 'listeners' to be an empty array",
                jsonResponse.containsKey("listeners") && jsonResponse.getJsonArray("listeners").isEmpty());
    }

    static class PUTRequest extends DummyHttpServerRequest {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();

        private String uri;
        private String body;

        public PUTRequest(String uri, String body) {
            this.uri = uri;
            this.body = body;
        }

        @Override
        public HttpMethod method() {
            return HttpMethod.PUT;
        }

        @Override
        public String uri() {
            return uri;
        }

        @Override
        public MultiMap headers() {
            return headers;
        }

        @Override
        public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
            bodyHandler.handle(Buffer.buffer(body));
            return this;
        }

        public void addHeader(String headerName, String headerValue) {
            headers.add(headerName, headerValue);
        }
    }

    static class GETRequest extends DummyHttpServerRequest {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        MultiMap params = MultiMap.caseInsensitiveMultiMap();
        private final HttpServerResponse response;
        private final String uri;

        public GETRequest(String uri, HttpServerResponse response) {
            this.uri = uri;
            this.response = response;
        }

        @Override
        public HttpMethod method() {
            return HttpMethod.GET;
        }

        @Override
        public String uri() {
            return uri;
        }

        @Override
        public MultiMap headers() {
            return headers;
        }

        @Override
        public MultiMap params() {
            return params;
        }

        @Override
        public HttpServerResponse response() {
            return response;
        }

        @Override
        public String getParam(String paramName) {
            return params.get(paramName);
        }

        public void addParameter(String paramName, String paramValue) {
            params.add(paramName, paramValue);
        }
    }
}

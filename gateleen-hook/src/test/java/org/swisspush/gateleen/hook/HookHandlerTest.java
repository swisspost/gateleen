package org.swisspush.gateleen.hook;

import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.buffer.impl.BufferImpl;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.*;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.hook.reducedpropagation.ReducedPropagationManager;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.queue.expiry.ExpiryCheckHandler;
import org.swisspush.gateleen.queue.queuing.RequestQueue;

import java.util.*;
import java.util.concurrent.CountDownLatch;

import static io.vertx.core.http.HttpMethod.PUT;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.*;
import static org.swisspush.gateleen.core.util.HttpRequestHeader.*;

/**
 * Tests for the {@link HookHandler} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class HookHandlerTest {

    private static final String HOOK_ROOT_URI = "hookRootURI/";
    private static final Logger logger = LoggerFactory.getLogger(HookHandlerTest.class);
    private Vertx vertx;
    private HttpClient httpClient;
    private MockResourceStorage storage;
    private LoggingResourceManager loggingResourceManager;
    private MonitoringHandler monitoringHandler;
    private RequestQueue requestQueue;
    private ReducedPropagationManager reducedPropagationManager;

    private HookHandler hookHandler;


    @Before
    public void setUp() {
        vertx = Vertx.vertx();
        httpClient = Mockito.mock(HttpClient.class);
        Mockito.when(httpClient.request(any(HttpMethod.class), anyString())).thenReturn(Mockito.mock(Future.class));
        storage = new MockResourceStorage();
        loggingResourceManager = Mockito.mock(LoggingResourceManager.class);
        monitoringHandler = Mockito.mock(MonitoringHandler.class);
        requestQueue = Mockito.mock(RequestQueue.class);
        reducedPropagationManager = Mockito.mock(ReducedPropagationManager.class);


        hookHandler = new HookHandler(vertx, httpClient, storage, loggingResourceManager, monitoringHandler,
                "userProfilePath", HOOK_ROOT_URI, requestQueue, false, reducedPropagationManager);
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

    private JsonObject buildListenerConfigWithHeadersFilter(JsonObject queueingStrategy, String deviceId, String headersFilter){
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
        hookHandler.handle(putRequest);

        // verify that enqueue has been called WITH the payload
        Mockito.verify(requestQueue, Mockito.timeout(2000).times(1)).enqueue(Mockito.argThat(new ArgumentMatcher<>() {
            @Override
            public boolean matches(Object argument) {
                HttpRequest req = (HttpRequest) argument;
                return HttpMethod.PUT == req.getMethod()
                        && req.getUri().contains(uri)
                        && Integer.valueOf(99).equals(getInteger(req.getHeaders(), CONTENT_LENGTH)) // Content-Length header should not have changed
                        && Arrays.equals(req.getPayload(), Buffer.buffer(originalPayload).getBytes()); // payload should not have changed
            }
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
        hookHandler.handle(putRequest);

        // verify that enqueue has been called WITH the payload
        Mockito.verify(requestQueue, Mockito.timeout(2000).times(1)).enqueue(Mockito.argThat(new ArgumentMatcher<>() {
            @Override
            public boolean matches(Object argument) {
                HttpRequest req = (HttpRequest) argument;
                return HttpMethod.PUT == req.getMethod()
                        && req.getUri().contains(uri)
                        && Integer.valueOf(99).equals(getInteger(req.getHeaders(), CONTENT_LENGTH)) // Content-Length header should not have changed
                        && Arrays.equals(req.getPayload(), Buffer.buffer(originalPayload).getBytes()); // payload should not have changed
            }
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
        hookHandler.handle(putRequest);

        // verify that enqueue has been called WITHOUT the payload but with 'Content-Length : 0' header
        Mockito.verify(requestQueue, Mockito.timeout(2000).times(1)).enqueue(Mockito.argThat(new ArgumentMatcher<>() {
            @Override
            public boolean matches(Object argument) {
                HttpRequest req = (HttpRequest) argument;
                return HttpMethod.PUT == req.getMethod()
                        && req.getUri().contains(uri)
                        && Integer.valueOf(0).equals(getInteger(req.getHeaders(), CONTENT_LENGTH))
                        && Arrays.equals(req.getPayload(), new byte[0]); // should not be original payload anymore
            }
        }), anyString(), any(Handler.class));

        PUTRequest putRequestWithoutContentLengthHeader = new PUTRequest(uri, originalPayload);
        hookHandler.handle(putRequestWithoutContentLengthHeader);

        // verify that enqueue has been called WITHOUT the payload and WITHOUT 'Content-Length' header
        Mockito.verify(requestQueue, Mockito.timeout(2000).times(1)).enqueue(Mockito.argThat(new ArgumentMatcher<>() {
            @Override
            public boolean matches(Object argument) {
                HttpRequest req = (HttpRequest) argument;
                return HttpMethod.PUT == req.getMethod()
                        && req.getUri().contains(uri)
                        && !containsHeader(req.getHeaders(), CONTENT_LENGTH)
                        && Arrays.equals(req.getPayload(), new byte[0]); // should not be original payload anymore
            }
        }), anyString(), any(Handler.class));
    }

    @Test
    public void testListenerEnqueueWithReducedPropagationQueueingStrategyButNoManager(TestContext context) throws InterruptedException {
        hookHandler = new HookHandler(vertx, httpClient, storage, loggingResourceManager, monitoringHandler,
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
        hookHandler.handle(putRequest);

        // verify that no enqueue (or lockedEnqueue) has been called because no ReducedPropagationManager was configured
        Mockito.verifyZeroInteractions(requestQueue);
        Mockito.verifyZeroInteractions(reducedPropagationManager);
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
        hookHandler.handle(putRequest);

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
        hookHandler.handle(putRequest);

        // verify that enqueue has been called WITH the payload
        Mockito.verify(requestQueue, Mockito.timeout(2000).times(1)).enqueue(Mockito.argThat(new ArgumentMatcher<>() {
            @Override
            public boolean matches(Object argument) {
                HttpRequest req = (HttpRequest) argument;
                return HttpMethod.PUT == req.getMethod()
                        && req.getUri().contains(uri)
                        && Integer.valueOf(99).equals(getInteger(req.getHeaders(), CONTENT_LENGTH)) // Content-Length header should not have changed
                        && Arrays.equals(req.getPayload(), Buffer.buffer(originalPayload).getBytes()); // payload should not have changed
            }
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
        hookHandler.handle(putRequest);

        // verify that enqueue has been called WITH the payload
        Mockito.verify(requestQueue, Mockito.timeout(2000).times(1)).enqueue(Mockito.argThat(new ArgumentMatcher<>() {
            @Override
            public boolean matches(Object argument) {
                HttpRequest req = (HttpRequest) argument;
                return HttpMethod.PUT == req.getMethod()
                        && req.getUri().contains(uri)
                        && Integer.valueOf(99).equals(getInteger(req.getHeaders(), CONTENT_LENGTH)) // Content-Length header should not have changed
                        && Arrays.equals(req.getPayload(), Buffer.buffer(originalPayload).getBytes()); // payload should not have changed
            }
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
        putRequest.addHeader("x-foo", "X"); // the request header x-foo: X should not trigger the listener
        hookHandler.handle(putRequest);

        // verify that no enqueue has been called since the header did not match
        Mockito.verifyZeroInteractions(requestQueue);
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
        hookHandler.handle(request);

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
        hookHandler.handle(request);

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
        hookHandler.handle(request);

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
        hookHandler.handle(request);

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
        hookHandler.handle(request);

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
            hookHandler.handle(request);

            { // Assert request got rejected.
                testContext.assertEquals(400, statusCodePtr[0]);
            }
        }
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
        hookHandler.handle(putRequest);
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
        final HttpServerRequest request = new FastFailHttpServerRequest() {
            @Override
            public HttpMethod method() {
                return method;
            }

            @Override
            public String uri() {
                return uri;
            }

            @Override
            public MultiMap headers() {
                return requestHeaders;
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
        return request;
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

    class PUTRequest extends DummyHttpServerRequest {
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
}

package org.swisspush.gateleen.hook;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Matchers;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.hook.reducedpropagation.ReducedPropagationManager;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.queue.queuing.RequestQueue;

import java.util.Arrays;

import static org.mockito.Matchers.*;
import static org.swisspush.gateleen.core.util.HttpRequestHeader.*;

/**
 * Tests for the {@link HookHandler} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class HookHandlerTest {

    private Vertx vertx;
    private HttpClient httpClient;
    private MockResourceStorage storage;
    private LoggingResourceManager loggingResourceManager;
    private MonitoringHandler monitoringHandler;
    private RequestQueue requestQueue;
    private ReducedPropagationManager reducedPropagationManager;

    private HookHandler hookHandler;

    @Before
    public void setUp(){
        vertx = Vertx.vertx();
        httpClient = Mockito.mock(HttpClient.class);
        Mockito.when(httpClient.request(any(HttpMethod.class), anyString(), Matchers.<Handler<HttpClientResponse>>any())).thenReturn(Mockito.mock(HttpClientRequest.class));
        storage = new MockResourceStorage();
        loggingResourceManager = Mockito.mock(LoggingResourceManager.class);
        monitoringHandler = Mockito.mock(MonitoringHandler.class);
        requestQueue = Mockito.mock(RequestQueue.class);
        reducedPropagationManager = Mockito.mock(ReducedPropagationManager.class);


        hookHandler = new HookHandler(vertx, httpClient, storage, loggingResourceManager, monitoringHandler,
                "userProfilePath", "hookRootUri", requestQueue, false, reducedPropagationManager);
        hookHandler.init();
    }

    private void setListenerStorageEntryAndTriggerUpdate(JsonObject listenerConfig){
        storage.putMockData("pathToListenerResource", listenerConfig.encode());
        vertx.eventBus().send("gateleen.hook-listener-insert", "pathToListenerResource");
    }

    private JsonObject buildListenerConfig(JsonObject queueingStrategy, String deviceId){
        JsonObject config = new JsonObject();
        config.put("requesturl", "/playground/server/tests/hooktest/_hooks/listeners/http/push/" + deviceId);
        config.put("expirationTime", "2017-01-03T14:15:53.277");

        JsonObject hook = new JsonObject();
        hook.put("destination", "/playground/server/push/v1/devices/" + deviceId);
        hook.put("methods", new JsonArray(Arrays.asList("PUT")));
        hook.put("expireAfter", 300);
        hook.put("fullUrl", true);
        JsonObject staticHeaders = new JsonObject();
        staticHeaders.put("x-sync", true);
        hook.put("staticHeaders", staticHeaders);

        if(queueingStrategy != null){
            hook.put("queueingStrategy", queueingStrategy);
        }

        config.put("hook", hook);
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
        Mockito.verify(requestQueue, Mockito.timeout(2000).times(1)).enqueue(Mockito.argThat(new ArgumentMatcher<HttpRequest>() {
            @Override
            public boolean matches(Object argument) {
                HttpRequest req = (HttpRequest) argument;
                return HttpMethod.PUT == req.getMethod()
                        && req.getUri().contains(uri)
                        && new Integer(99).equals(getInteger(req.getHeaders(), CONTENT_LENGTH)) // Content-Length header should not have changed
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
        Mockito.verify(requestQueue, Mockito.timeout(2000).times(1)).enqueue(Mockito.argThat(new ArgumentMatcher<HttpRequest>() {
            @Override
            public boolean matches(Object argument) {
                HttpRequest req = (HttpRequest) argument;
                return HttpMethod.PUT == req.getMethod()
                        && req.getUri().contains(uri)
                        && new Integer(99).equals(getInteger(req.getHeaders(), CONTENT_LENGTH)) // Content-Length header should not have changed
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
        Mockito.verify(requestQueue, Mockito.timeout(2000).times(1)).enqueue(Mockito.argThat(new ArgumentMatcher<HttpRequest>() {
            @Override
            public boolean matches(Object argument) {
                HttpRequest req = (HttpRequest) argument;
                return HttpMethod.PUT == req.getMethod()
                        && req.getUri().contains(uri)
                        && new Integer(0).equals(getInteger(req.getHeaders(), CONTENT_LENGTH))
                        && Arrays.equals(req.getPayload(), new byte[0]); // should not be original payload anymore
            }
        }), anyString(), any(Handler.class));

        PUTRequest putRequestWithoutContentLengthHeader = new PUTRequest(uri, originalPayload);
        hookHandler.handle(putRequestWithoutContentLengthHeader);

        // verify that enqueue has been called WITHOUT the payload and WITHOUT 'Content-Length' header
        Mockito.verify(requestQueue, Mockito.timeout(2000).times(1)).enqueue(Mockito.argThat(new ArgumentMatcher<HttpRequest>() {
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
                "userProfilePath", "hookRootUri", requestQueue, false, null);
        hookHandler.init();

        // trigger listener update via event bus
        setListenerStorageEntryAndTriggerUpdate(buildListenerConfig(new JsonObject().put("type", "reducedPropagation").put("interval", 22), "x99"));

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
        String queue = "listener-hook-http+push+"+deviceId+"+playground+server+tests+hooktest";

        // trigger listener update via event bus
        setListenerStorageEntryAndTriggerUpdate(buildListenerConfig(new JsonObject().put("type", "reducedPropagation").put("interval", interval), deviceId));

        // wait a moment to let the listener be registered
        Thread.sleep(1500);

        // make a change to the hooked resource
        String uri = "/playground/server/tests/hooktest/abc123";
        String originalPayload = "{\"key\":123}";
        PUTRequest putRequest = new PUTRequest(uri, originalPayload);
        putRequest.addHeader(CONTENT_LENGTH.getName(), "99");
        hookHandler.handle(putRequest);

        String targetUri = "/playground/server/push/v1/devices/"+deviceId+"/playground/server/tests/hooktest/abc123";
        Mockito.verify(reducedPropagationManager, Mockito.timeout(2000).times(1))
                .processIncomingRequest(eq(HttpMethod.PUT), eq(targetUri), any(MultiMap.class), eq(Buffer.buffer(originalPayload)), eq(queue), eq(interval), any(Handler.class));
    }

    @Test
    public void testListenerEnqueueWithInvalidReducedPropagationQueueingStrategy(TestContext context) throws InterruptedException {
        // trigger listener update via event bus
        setListenerStorageEntryAndTriggerUpdate(buildListenerConfig(new JsonObject().put("type", "reducedPropagation").put("interval", "not_a_number"), "x99")); // invalid 'queueingStrategy' configuration results in a DefaultQueueingStrategy

        // wait a moment to let the listener be registered
        Thread.sleep(1000);

        // make a change to the hooked resource
        String uri = "/playground/server/tests/hooktest/abc123";
        String originalPayload = "{\"key\":123}";
        PUTRequest putRequest = new PUTRequest(uri, originalPayload);
        putRequest.addHeader(CONTENT_LENGTH.getName(), "99");
        hookHandler.handle(putRequest);

        // verify that enqueue has been called WITH the payload
        Mockito.verify(requestQueue, Mockito.timeout(2000).times(1)).enqueue(Mockito.argThat(new ArgumentMatcher<HttpRequest>() {
            @Override
            public boolean matches(Object argument) {
                HttpRequest req = (HttpRequest) argument;
                return HttpMethod.PUT == req.getMethod()
                        && req.getUri().contains(uri)
                        && new Integer(99).equals(getInteger(req.getHeaders(), CONTENT_LENGTH)) // Content-Length header should not have changed
                        && Arrays.equals(req.getPayload(), Buffer.buffer(originalPayload).getBytes()); // payload should not have changed
            }
        }), anyString(), any(Handler.class));
    }

    class PUTRequest extends DummyHttpServerRequest {
        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();

        private String uri;
        private String body;

        public PUTRequest(String uri, String body) {
            this.uri = uri;
            this.body = body;
        }

        @Override public HttpMethod method() {
            return HttpMethod.PUT;
        }
        @Override public String uri() {
            return uri;
        }
        @Override public MultiMap headers() { return headers; }

        @Override
        public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
            bodyHandler.handle(Buffer.buffer(body));
            return this;
        }

        public void addHeader(String headerName, String headerValue){ headers.add(headerName, headerValue); }
    }
}

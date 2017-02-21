package org.swisspush.gateleen.hook;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.ext.unit.Async;
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
import org.swisspush.gateleen.core.util.HttpRequestHeader;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.queue.queuing.RequestQueue;

import java.util.Arrays;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;

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

        hookHandler = new HookHandler(vertx, httpClient, storage, loggingResourceManager, monitoringHandler,
                "userProfilePath", "hookRootUri", requestQueue, false);
        hookHandler.init();
    }

    private void setListenerStorageEntryAndTriggerUpdate(boolean discardPayload){
        storage.putMockData("pathToListenerResource", "{\n" +
                "\t\"requesturl\": \"/playground/server/tests/hooktest/_hooks/listeners/http/push/test1\",\n" +
                "\t\"expirationTime\": \"2017-02-20T15:51:11.018\",\n" +
                "\t\"hook\": {\n" +
                "\t\t\"destination\": \"/playground/server/event/v1/channels/test1\",\n" +
                "\t\t\"methods\": [\"PUT\"],\n" +
                "\t\t\"expireAfter\": 300,\n" +
                "\t\t\"fullUrl\": true,\n" +
                "\t\t\"discardPayload\": "+discardPayload+",\n" +
                "\t\t\"staticHeaders\": {\n" +
                "\t\t\t\"x-sync\": true\n" +
                "\t\t}\n" +
                "\t}\n" +
                "}");
        vertx.eventBus().send("gateleen.hook-listener-insert", "pathToListenerResource");
    }

    @Test
    public void testListenerEnqueueWithOriginalPayload(TestContext context) throws InterruptedException {
        Async async = context.async();

        // trigger listener update via event bus
        setListenerStorageEntryAndTriggerUpdate(false); // false = don't discard payload

        // wait a moment to let the listener be registered
        Thread.sleep(1000);

        // make a change to the hooked resource
        String uri = "/playground/server/tests/hooktest/abc123";
        String originalPayload = "{\"key\":123}";
        hookHandler.handle(new PUTRequest(uri, originalPayload));

        // verify that enqueue has been called WITH the payload
        Mockito.verify(requestQueue, Mockito.timeout(2000).times(1)).enqueue(Mockito.argThat(new ArgumentMatcher<HttpRequest>() {
            @Override
            public boolean matches(Object argument) {
                HttpRequest req = (HttpRequest) argument;
                return HttpMethod.PUT == req.getMethod()
                        && req.getUri().contains(uri)
                        && Arrays.equals(req.getPayload(), Buffer.buffer(originalPayload).getBytes());
            }
        }), anyString(), any(Handler.class));
        async.complete();
    }

    @Test
    public void testListenerEnqueueWithDiscardedPayload(TestContext context) throws InterruptedException {
        Async async = context.async();

        // trigger listener update via event bus
        setListenerStorageEntryAndTriggerUpdate(true); // true = discard payload

        // wait a moment to let the listener be registered
        Thread.sleep(1000);

        // make a change to the hooked resource
        String uri = "/playground/server/tests/hooktest/abc123";
        String originalPayload = "{\"key\":123}";
        hookHandler.handle(new PUTRequest(uri, originalPayload));

        // verify that enqueue has been called WITH the payload
        Mockito.verify(requestQueue, Mockito.timeout(2000).times(1)).enqueue(Mockito.argThat(new ArgumentMatcher<HttpRequest>() {
            @Override
            public boolean matches(Object argument) {
                HttpRequest req = (HttpRequest) argument;
                return HttpMethod.PUT == req.getMethod()
                        && req.getUri().contains(uri)
                        && new Integer(5).equals(HttpRequestHeader.getInteger(req.getHeaders(), HttpRequestHeader.CONTENT_LENGTH))
                        && Arrays.equals(req.getPayload(), new byte[0]); // should not be original payload anymore
            }
        }), anyString(), any(Handler.class));
        async.complete();
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
    }
}

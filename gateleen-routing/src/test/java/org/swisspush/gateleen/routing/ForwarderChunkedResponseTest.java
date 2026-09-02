package org.swisspush.gateleen.routing;

import com.google.common.collect.ImmutableMap;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.RoutingContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.http.DummyHttpServerResponse;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.logging.LogAppenderRepository;
import org.swisspush.gateleen.logging.LoggingResourceManager;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests how {@link Forwarder} translates the transfer framing of an upstream response
 * into the downstream response.
 *
 * <p>An upstream response carrying {@code Transfer-Encoding: chunked} but no payload
 * must not be forwarded as a chunked response. Forwarding it as chunked produced a
 * "chunked response without body", so chunked encoding is now enabled just-in-time,
 * right before the first non-empty buffer is written.
 */
@RunWith(VertxUnitRunner.class)
public class ForwarderChunkedResponseTest {

    private static final String LOGGING_URI = "/gateleen/server/admin/v1/logging";
    private static final String RULES_PATH = "/gateleen/server/admin/v1/routing/rules";
    private static final String USER_PROFILE_PATH = "/gateleen/server/users/v1/%s/profile";
    private static final String REQUEST_URI = "/test/resource";
    private static final String TRANSFER_ENCODING = "Transfer-Encoding";

    private Vertx vertx;
    private HttpServer backend;

    @Before
    public void setUp() {
        vertx = Vertx.vertx();
    }

    @After
    public void tearDown(TestContext ctx) {
        vertx.close(ctx.asyncAssertSuccess());
    }

    /**
     * The upstream answers with {@code Transfer-Encoding: chunked} but never writes any
     * payload. The downstream response must therefore not be switched to chunked, and the
     * upstream transfer framing header must not be forwarded.
     */
    @Test
    public void testChunkedUpstreamResponseWithoutBody_downstreamIsNotChunked(TestContext ctx) {
        int port = startBackend(ctx, response -> response.setChunked(true).end());

        Async async = ctx.async();
        CapturingResponse dnRsp = new CapturingResponse(async);

        forwarderFor(port).handle(routingContextFor(dnRsp));

        async.awaitSuccess(5000);

        ctx.assertFalse(dnRsp.chunkedEnabled.get(),
                "Downstream response must not be chunked when the upstream sent no payload");
        ctx.assertEquals(0, dnRsp.body.length(), "Downstream response must have an empty body");
        ctx.assertNull(dnRsp.headers().get(TRANSFER_ENCODING),
                "Upstream transfer framing header must not be forwarded downstream");
    }

    /**
     * The upstream answers chunked and does write payload. Here chunked encoding is
     * legitimate, so it must be enabled on the downstream response and the payload
     * must be forwarded unchanged.
     */
    @Test
    public void testChunkedUpstreamResponseWithBody_downstreamIsChunked(TestContext ctx) {
        int port = startBackend(ctx, response -> {
            response.setChunked(true);
            response.write("hello ");
            response.end("world");
        });

        Async async = ctx.async();
        CapturingResponse dnRsp = new CapturingResponse(async);

        forwarderFor(port).handle(routingContextFor(dnRsp));

        async.awaitSuccess(5000);

        ctx.assertTrue(dnRsp.chunkedEnabled.get(),
                "Downstream response must be chunked when the upstream streamed a payload");
        ctx.assertEquals("hello world", dnRsp.body.toString(),
                "Payload must be forwarded unchanged");
    }

    /**
     * A response with a {@code Content-Length} must keep using that framing, so chunked
     * encoding must never be enabled - not even once payload is written.
     */
    @Test
    public void testContentLengthUpstreamResponse_downstreamIsNotChunked(TestContext ctx) {
        int port = startBackend(ctx, response -> response.end("hello world"));

        Async async = ctx.async();
        CapturingResponse dnRsp = new CapturingResponse(async);

        forwarderFor(port).handle(routingContextFor(dnRsp));

        async.awaitSuccess(5000);

        ctx.assertFalse(dnRsp.chunkedEnabled.get(),
                "A Content-Length response must not be switched to chunked encoding");
        ctx.assertEquals("hello world", dnRsp.body.toString(),
                "Payload must be forwarded unchanged");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private int startBackend(TestContext ctx, Handler<HttpServerResponse> responder) {
        Async backendReady = ctx.async();
        // AtomicInteger (rather than a plain int[]) makes the cross-thread handoff explicit:
        // the value is written on the event-loop thread inside the listen() callback and read
        // on the test thread only after backendReady.awaitSuccess() below returns, i.e. only
        // after backendReady.complete() - and therefore the write - has already happened.
        AtomicInteger port = new AtomicInteger(-1);
        backend = vertx.createHttpServer();
        backend.requestHandler(req -> responder.handle(req.response()));
        backend.listen(0, ctx.asyncAssertSuccess(server -> {
            port.set(server.actualPort());
            backendReady.complete();
        }));
        backendReady.awaitSuccess(5000);
        return port.get();
    }

    private Forwarder forwarderFor(int port) {
        Rule rule = new Rule();
        rule.setScheme("http");
        rule.setHost("localhost");
        rule.setPort(port);
        rule.setTimeout(5000);
        rule.setUrlPattern(REQUEST_URI);
        rule.setPath(REQUEST_URI);
        rule.setKeepAlive(false);
        rule.setPoolSize(1);
        rule.setMaxWaitQueueSize(-1);

        MockResourceStorage storage = new MockResourceStorage(ImmutableMap.of(RULES_PATH, "{}"));
        HttpClient httpClient = vertx.createHttpClient(rule.buildHttpClientOptions());
        return new Forwarder(
                vertx,
                httpClient,
                rule,
                storage,
                new LoggingResourceManager(vertx, storage, LOGGING_URI),
                mock(LogAppenderRepository.class),
                null,
                USER_PROFILE_PATH,
                null
        );
    }

    private RoutingContext routingContextFor(HttpServerResponse response) {
        DummyHttpServerRequest request = new DummyHttpServerRequest() {
            @Override public HttpMethod method() { return HttpMethod.GET; }
            @Override public String uri() { return REQUEST_URI; }
            @Override public String path() { return REQUEST_URI; }
            @Override public MultiMap headers() { return new HeadersMultiMap(); }
            @Override public HttpServerResponse response() { return response; }
            @Override public boolean isEnded() { return true; }
            @Override public DummyHttpServerRequest pause() { return this; }
            @Override public DummyHttpServerRequest resume() { return this; }
            @Override public HttpConnection connection() { return null; }
            @Override public DummyHttpServerRequest exceptionHandler(Handler<Throwable> handler) { return this; }
        };

        RoutingContext routingCtx = mock(RoutingContext.class);
        when(routingCtx.request()).thenReturn(request);
        return routingCtx;
    }

    /**
     * Downstream response recording whether chunked encoding got enabled and which
     * payload was written.
     */
    private static class CapturingResponse extends DummyHttpServerResponse {

        private final AtomicBoolean chunkedEnabled = new AtomicBoolean();
        private final Buffer body = Buffer.buffer();
        private final Async async;

        private CapturingResponse(Async async) {
            this.async = async;
        }

        @Override
        public HttpServerResponse setChunked(boolean chunked) {
            chunkedEnabled.set(chunked);
            return this;
        }

        @Override
        public boolean isChunked() {
            return chunkedEnabled.get();
        }

        @Override
        public Future<Void> write(Buffer data) {
            body.appendBuffer(data);
            return Future.succeededFuture();
        }

        @Override
        public void write(Buffer data, Handler<AsyncResult<Void>> handler) {
            body.appendBuffer(data);
            handler.handle(Future.succeededFuture());
        }

        @Override
        public Future<Void> end() {
            async.complete();
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> end(Buffer chunk) {
            body.appendBuffer(chunk);
            async.complete();
            return Future.succeededFuture();
        }

        @Override
        public HttpServerResponse exceptionHandler(Handler<Throwable> handler) { return this; }

        @Override
        public HttpServerResponse setWriteQueueMaxSize(int maxSize) { return this; }

        @Override
        public boolean writeQueueFull() { return false; }

        @Override
        public HttpServerResponse drainHandler(Handler<Void> handler) { return this; }

        @Override
        public boolean headWritten() { return false; }
    }
}

package org.swisspush.gateleen.routing;

import com.google.common.collect.ImmutableMap;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
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
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.logging.LogAppenderRepository;
import org.swisspush.gateleen.logging.LoggingResourceManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link Forwarder} behavior under backend failure conditions.
 *
 * <p>Covers two scenarios that reproduce a production bug where forwarded requests
 * ran for over 90 minutes without receiving any gateway error response:
 *
 * <ul>
 *   <li><b>Immediate connection close:</b> The backend accepts the TCP connection and
 *       the request, then closes the connection before writing any HTTP response.
 *       The Forwarder correctly returns 502 Bad Gateway.</li>
 *   <li><b>Exhausted connection pool:</b> The backend accepts connections but never
 *       responds (simulating a half-open connection kept alive by an intermediate load
 *       balancer). Once the single pool connection is occupied by a hanging request,
 *       subsequent requests enter the pool's unbounded wait queue. No timeout is ever
 *       armed on queued requests because {@code onNewRequestComplete} — the point where
 *       {@code idleTimeout} is set — never fires for them. These requests hang
 *       indefinitely, beyond the configured timeout.</li>
 * </ul>
 */
@RunWith(VertxUnitRunner.class)
public class ForwarderConnectionCloseTest {

    private static final String LOGGING_URI = "/gateleen/server/admin/v1/logging";
    private static final String RULES_PATH = "/gateleen/server/admin/v1/routing/rules";
    private static final String USER_PROFILE_PATH = "/gateleen/server/users/v1/%s/profile";
    private static final String REQUEST_URI = "/test/resource";

    private Vertx vertx;
    private HttpServer closingBackend;
    private int closingBackendPort;

    @Before
    public void setUp(TestContext ctx) {
        vertx = Vertx.vertx();
        Async serverReady = ctx.async();
        closingBackend = vertx.createHttpServer();
        // Backend accepts the connection and the full request, then immediately closes
        // the TCP connection without writing any HTTP response.
        closingBackend.requestHandler(req -> req.connection().close());
        closingBackend.listen(0, ctx.asyncAssertSuccess(server -> {
            closingBackendPort = server.actualPort();
            serverReady.complete();
        }));
    }

    @After
    public void tearDown(TestContext ctx) {
        vertx.close(ctx.asyncAssertSuccess());
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * Reproduces the production scenario where the backend closes the TCP connection
     * before sending any HTTP response.
     *
     * <p>When the backend closes the connection immediately after receiving the request,
     * the upstream response {@link io.vertx.core.Future} fails with a connection-reset error.
     * This is delivered via the response future failure callback in
     * {@code Forwarder.onNewRequestComplete} which calls {@code tryRespondWithBadGateway},
     * resulting in a <b>502 Bad Gateway</b> response.
     */
    @Test
    public void testBackendClosesConnectionBeforeResponse_shouldReturn502(TestContext ctx) {
        Async async = ctx.async();
        AtomicInteger capturedStatus = new AtomicInteger(-1);

        Rule rule = buildRule(closingBackendPort, 500);
        HttpClient httpClient = vertx.createHttpClient(rule.buildHttpClientOptions());
        Forwarder forwarder = buildForwarder(rule, httpClient);

        forwarder.handle(buildRoutingContext(buildCapturingResponse(async, capturedStatus)));

        // Wait up to 3 seconds — well above the 500 ms idle timeout.
        async.awaitSuccess(3000);

        // When the backend closes the connection before sending any HTTP response, the
        // upstream response Future fails with a connection-reset error. The bad-gateway
        // path in onNewRequestComplete (Forwarder.java:401-406) fires:
        //   ctx.upReq.response(ev -> { if (ev.failed()) { tryRespondWithBadGateway(...) } })
        // resulting in a 502 Bad Gateway response to the downstream client.
        ctx.assertEquals(
                StatusCode.BAD_GATEWAY.getStatusCode(),
                capturedStatus.get(),
                "Expected 502 Bad Gateway when backend closes connection before responding, " +
                        "but got: " + capturedStatus.get()
        );
    }

    /**
     * Reproduces the production bug where requests hang indefinitely when the connection
     * pool is exhausted.
     *
     * <p>Setup: pool size is 1, the backend accepts connections but never writes any
     * response and never closes the connection (simulating a half-open connection kept
     * alive by an intermediate load balancer).
     *
     * <p>Request #1 acquires the only pool connection. {@code onNewRequestComplete} fires
     * and {@code idleTimeout} is armed with 2000 ms. The test observes for only 1500 ms,
     * so req1's timeout has not yet fired during the observation window — the connection
     * remains occupied.
     *
     * <p>Request #2 finds the pool full and enters the pool's unbounded wait queue.
     * {@code onNewRequestComplete} <em>never fires</em> for this request because no
     * connection becomes available. Therefore {@code idleTimeout} is <em>never armed</em>,
     * and the request hangs indefinitely — well beyond the configured timeout — with no
     * error response sent to the downstream client. This is the actual production bug.
     */
    @Test
    public void testExhaustedConnectionPool_requestsHangBeyondConfiguredTimeout(TestContext ctx) {
        // --- 1. Open a raw ServerSocket that accepts connections and reads the request
        //        but never writes any response — simulating a half-open LB connection.
        //        Using a raw ServerSocket (not a Vert.x HttpServer) ensures that Vert.x
        //        has no opportunity to close the server-side connection automatically.
        final java.net.ServerSocket serverSocket;
        try {
            serverSocket = new java.net.ServerSocket(0);
        } catch (java.io.IOException e) {
            ctx.fail(e);
            return;
        }
        int[] silentPort = new int[]{serverSocket.getLocalPort()};
        // Accept connections in a background thread and hold them open indefinitely.
        Thread acceptThread = new Thread(() -> {
            java.util.List<java.net.Socket> held = new java.util.ArrayList<>();
            try {
                serverSocket.setSoTimeout(5000); // unblock accept() when test ends
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        java.net.Socket client = serverSocket.accept();
                        client.setTcpNoDelay(true);
                        held.add(client); // hold — never close, never write anything
                    } catch (java.net.SocketTimeoutException e) {
                        break;
                    }
                }
            } catch (Exception ignored) { }
        });
        acceptThread.setDaemon(true);
        acceptThread.start();

        // --- 2. Rule: pool size 1, timeout 2000 ms (longer than the 1500 ms observation
        //        window), keep-alive so the client does not send Connection:close.
        //        Using a timeout > observation window means req2 will still be in the
        //        pool wait queue when we check — proving the indefinite-hang scenario.
        Rule rule = buildRule(silentPort[0], 2000);
        rule.setKeepAlive(true);
        HttpClient httpClient = vertx.createHttpClient(rule.buildHttpClientOptions());
        Forwarder forwarder = buildForwarder(rule, httpClient);

        // --- 3. Request #1: acquires the only pool connection, holds it until timeout ---
        // Use CountDownLatches instead of Async so that incomplete requests do not
        // cause the TestContext to fail with a mandatory-completion timeout.
        java.util.concurrent.CountDownLatch req1Latch = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger capturedStatus1 = new AtomicInteger(-1);
        forwarder.handle(buildRoutingContext(buildCapturingResponse(req1Latch, capturedStatus1)));

        // --- 4. Request #2: pool is full, enters the unbounded wait queue ---
        java.util.concurrent.CountDownLatch req2Latch = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger capturedStatus2 = new AtomicInteger(-1);
        forwarder.handle(buildRoutingContext(buildCapturingResponse(req2Latch, capturedStatus2)));

        // --- 5. Observe for 1500 ms — less than the 2000 ms configured timeout ---
        final boolean req1Done;
        final boolean req2Done;
        try {
            req1Done = req1Latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
            req2Done = req2Latch.await(0, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ctx.fail("Test interrupted");
            return;
        }

        // Request #1 is still holding the pool connection and waiting for a response.
        // The 2000 ms idleTimeout has not yet fired (we only waited 1500 ms), so req1
        // has not yet received any response.
        ctx.assertFalse(
                req1Done,
                "Request #1 should still be waiting — idleTimeout (2000 ms) has not fired yet"
        );

        // Request #2 never obtained a connection — it is stuck in the pool wait queue.
        // onNewRequestComplete never fired, so idleTimeout was never armed.
        // After 1500 ms (well within the 2000 ms timeout) the downstream client has still
        // received no response. This is the actual production bug: requests in the pool
        // wait queue have no timeout guard of their own.
        ctx.assertFalse(
                req2Done,
                "Request #2 should still be hanging after 1500 ms — it is " +
                        "stuck in the pool wait queue with no timeout guard"
        );
        ctx.assertEquals(
                -1,
                capturedStatus2.get(),
                "Expected no response status for request #2 (still hanging), but got: " +
                        capturedStatus2.get()
        );
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Forwarder buildForwarder(Rule rule, HttpClient httpClient) {
        MockResourceStorage storage = new MockResourceStorage(ImmutableMap.of(RULES_PATH, "{}"));
        LoggingResourceManager loggingResourceManager = new LoggingResourceManager(vertx, storage, LOGGING_URI);
        LogAppenderRepository logAppenderRepository = mock(LogAppenderRepository.class);
        return new Forwarder(
                vertx,
                httpClient,
                rule,
                storage,
                loggingResourceManager,
                logAppenderRepository,
                null,
                USER_PROFILE_PATH,
                null
        );
    }

    /**
     * Builds a {@link RoutingContext} mock whose {@code request()} returns a
     * {@link DummyHttpServerRequest} wrapping the given response.
     */
    private RoutingContext buildRoutingContext(DummyHttpServerResponse response) {
        DummyHttpServerRequest fakeRequest = new DummyHttpServerRequest() {
            @Override public HttpMethod method()    { return HttpMethod.GET; }
            @Override public String uri()           { return REQUEST_URI; }
            @Override public String path()          { return REQUEST_URI; }
            @Override public MultiMap headers()     { return new HeadersMultiMap(); }
            @Override public HttpServerResponse response() { return response; }

            // Tell Forwarder the body is already consumed — it calls upReq.send()
            // directly without setting up a Pump, keeping the code path simple.
            @Override public boolean isEnded()      { return true; }

            // Forwarder calls pause() before the async auth check, resume() after.
            @Override public DummyHttpServerRequest pause()  { return this; }
            @Override public DummyHttpServerRequest resume() { return this; }

            // Forwarder registers an exceptionHandler to close the upstream connection
            // when the downstream client disconnects.
            @Override public DummyHttpServerRequest exceptionHandler(Handler<Throwable> handler) {
                return this;
            }
        };

        RoutingContext routingCtx = mock(RoutingContext.class);
        when(routingCtx.request()).thenReturn(fakeRequest);
        return routingCtx;
    }

    /**
     * Builds a {@link DummyHttpServerResponse} that records the status code and
     * counts down the given {@link java.util.concurrent.CountDownLatch} when any
     * {@code end()} variant is called.
     */
    private DummyHttpServerResponse buildCapturingResponse(
            java.util.concurrent.CountDownLatch latch, AtomicInteger capturedStatus) {
        return new DummyHttpServerResponse() {
            @Override
            public Future<Void> end() {
                capturedStatus.set(getStatusCode());
                latch.countDown();
                return Future.succeededFuture();
            }

            @Override
            public Future<Void> end(String chunk) {
                capturedStatus.set(getStatusCode());
                latch.countDown();
                return Future.succeededFuture();
            }

            @Override
            public Future<Void> end(Buffer chunk) {
                capturedStatus.set(getStatusCode());
                latch.countDown();
                return Future.succeededFuture();
            }

            @Override
            public HttpServerResponse setChunked(boolean chunked) { return this; }
        };
    }

    /**
     * Builds a {@link DummyHttpServerResponse} that records the status code and
     * completes the given {@link Async} when any {@code end()} variant is called.
     */
    private DummyHttpServerResponse buildCapturingResponse(Async async, AtomicInteger capturedStatus) {
        return new DummyHttpServerResponse() {
            @Override
            public Future<Void> end() {
                capturedStatus.set(getStatusCode());
                async.complete();
                return Future.succeededFuture();
            }

            @Override
            public Future<Void> end(String chunk) {
                capturedStatus.set(getStatusCode());
                async.complete();
                return Future.succeededFuture();
            }

            @Override
            public Future<Void> end(Buffer chunk) {
                capturedStatus.set(getStatusCode());
                async.complete();
                return Future.succeededFuture();
            }

            @Override
            public HttpServerResponse setChunked(boolean chunked) { return this; }
        };
    }

    private static Rule buildRule(int port, int timeoutMs) {
        Rule rule = new Rule();
        rule.setScheme("http");
        rule.setHost("localhost");
        rule.setPort(port);
        rule.setTimeout(timeoutMs);
        rule.setUrlPattern(REQUEST_URI);
        rule.setPath(REQUEST_URI);
        rule.setKeepAlive(false);
        rule.setPoolSize(1);
        rule.setMaxWaitQueueSize(-1);
        return rule;
    }
}

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
     * Regression test for the pool-exhaustion fix.
     *
     * <p>Verifies that after the fix — a pool-wait guard timer armed in
     * {@code Forwarder.handleRequest} before {@code client.request()} is called —
     * a request stuck in the pool wait queue receives a timeout error response
     * within the configured timeout, even when the backend never responds and never
     * closes the connection.
     *
     * <p>Two outcomes are possible depending on event-loop scheduling:
     * <ul>
     *   <li>Pool-wait timer wins: req2 receives 504 at ~500 ms</li>
     *   <li>Pool callback wins: req2 gets a connection once req1's slot is freed at
     *       ~500 ms, then its own {@code idleTimeout} fires → 502 at ~1000 ms</li>
     * </ul>
     * In both cases the request completes within the 2000 ms observation window.
     * Before the fix, req2 would hang indefinitely with no response.
     */
    @Test
    public void testExhaustedConnectionPool_req2TimesOutWith504AfterFix(TestContext ctx) {
        // --- 1. Vert.x NetServer that accepts connections, drains all incoming bytes
        //        (so the HTTP client can fully write its request and the channel becomes
        //        idle), but never writes any HTTP response back.
        //        A raw ServerSocket would block Netty writes once the OS receive buffer
        //        fills, preventing idleTimeout from firing on the second request.
        Async backendReady = ctx.async();
        io.vertx.core.net.NetServer silentNetServer = vertx.createNetServer();
        silentNetServer.connectHandler(socket -> {
            // Drain all incoming bytes so the client-side write completes and the
            // connection enters an idle state where idleTimeout can fire.
            socket.handler(buf -> { /* discard — never write a response */ });
        });
        int[] silentPort = new int[1];
        silentNetServer.listen(0, ctx.asyncAssertSuccess(s -> {
            silentPort[0] = s.actualPort();
            backendReady.complete();
        }));
        backendReady.awaitSuccess(2000);

        // --- 2. Rule: pool size 1, timeout 500 ms, keep-alive=false (production default) ---
        Rule rule = buildRule(silentPort[0], 500);
        HttpClient httpClient = vertx.createHttpClient(rule.buildHttpClientOptions());
        Forwarder forwarder = buildForwarder(rule, httpClient);

        // --- 3. Request #1: acquires the pool connection, pool-wait timer armed ---
        java.util.concurrent.CountDownLatch req1Latch = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger capturedStatus1 = new AtomicInteger(-1);
        forwarder.handle(buildRoutingContext(buildCapturingResponse(req1Latch, capturedStatus1)));

        // Give req1 time to acquire the pool connection before dispatching req2.
        // Without this pause, with keepAlive=false, req2 might open its own TCP
        // connection before req1 has claimed the single pool slot.
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // --- 4. Request #2: pool full, enters wait queue, pool-wait timer armed ---
        java.util.concurrent.CountDownLatch req2Latch = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger capturedStatus2 = new AtomicInteger(-1);
        forwarder.handle(buildRoutingContext(buildCapturingResponse(req2Latch, capturedStatus2)));

        // --- 5. Observe for 2000 ms (4× the 500 ms timeout) ---
        // Two scenarios are possible depending on event-loop scheduling:
        //
        // A) Pool-wait timer wins the race (fires before pool slot is freed):
        //    req2 gets 504 at ~500 ms.
        //
        // B) Pool callback wins the race (pool slot freed by req1's idleTimeout at ~500 ms,
        //    req2 gets a connection, cancelTimer runs, req2's idleTimeout fires at ~1000 ms):
        //    req2 gets 502 at ~1000 ms.
        //
        // In both cases req2 must complete well within 2000 ms.
        // Before the fix, req2 would hang indefinitely (no response ever sent).
        final boolean req1Done;
        final boolean req2Done;
        try {
            req1Done = req1Latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS);
            req2Done = req2Latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ctx.fail("Test interrupted");
            return;
        }

        ctx.assertTrue(req1Done, "Request #1 should have completed within 2000 ms");
        ctx.assertTrue(req2Done,
                "Request #2 should have completed within 2000 ms — " +
                        "either the pool-wait guard timer (504) or idleTimeout (502) must fire. " +
                        "Before the fix, this request would hang indefinitely.");

        // Both requests must have received a timeout-class error response.
        // 504 = pool-wait timer fired first; 502 = idleTimeout fired after connection obtained.
        ctx.assertTrue(
                capturedStatus1.get() == StatusCode.TIMEOUT.getStatusCode() ||
                capturedStatus1.get() == StatusCode.BAD_GATEWAY.getStatusCode(),
                "Expected 504 or 502 for request #1, got: " + capturedStatus1.get()
        );
        ctx.assertTrue(
                capturedStatus2.get() == StatusCode.TIMEOUT.getStatusCode() ||
                capturedStatus2.get() == StatusCode.BAD_GATEWAY.getStatusCode(),
                "Expected 504 or 502 for request #2, got: " + capturedStatus2.get()
        );
    }

    /**
     * Regression test for the request-wide deadline fix.
     *
     * <p>The route has a single pool connection and a configured timeout of 1000 ms.
     * Request #1 occupies that sole connection and the backend releases it only near the
     * end of the configured timeout window (after ~700 ms). Request #2 is queued the whole
     * time and only acquires a connection once request #1's slot is freed, to a backend that
     * then never responds.
     *
     * <p>Request #2 is buffered and explicitly enables retries. Because the pool wait already
     * consumed most of its request-wide deadline, the
     * upstream {@code idleTimeout} armed for request #2 must use the <em>remaining</em> budget
     * (~300 ms), so request #2's final downstream response arrives within approximately one
     * configured timeout window (~1000 ms after it was dispatched).
     *
     * <p>Before the fix, request #2 was granted a fresh full idle window after acquiring the
     * connection, so its response only arrived after roughly the pool-wait span (~700 ms) plus
     * a full idle window (~1000 ms) ≈ 1700 ms. The 1500 ms assertion window therefore fails
     * without the fix and passes with it.
     */
    @Test
    public void testPoolWaitConsumesRequestWideDeadline_req2RespondsWithinOneTimeoutWindow(TestContext ctx) {
        assertPoolWaitTimeout(ctx, true);
    }

    /**
     * Counterpart of {@link #testPoolWaitConsumesRequestWideDeadline_req2RespondsWithinOneTimeoutWindow}
     * with retries disabled ({@code x-route-retry-5xx: 0}): no request-wide deadline is armed, so
     * request #2 keeps the original per-phase behaviour. Pool waiting does not reduce its idle
     * timeout, which still gets a full window (~1000 ms) after the connection was acquired, so the
     * 502 arrives after roughly pool wait plus a full idle window (1500–2900 ms).
     */
    @Test
    public void testPoolWaitWithoutRetries_retainsSeparateIdleTimeout(TestContext ctx) {
        assertPoolWaitTimeout(ctx, false);
    }

    /**
     * Shared scenario for the pool-wait tests: a single-connection pool with a 1000 ms timeout,
     * request #1 occupies the connection for ~700 ms and request #2 (buffered GET) is queued
     * meanwhile and then hits a backend that never answers. Asserts that request #2 ends with
     * 502, that both requests acquired a connection and that the timing matches the mode:
     * one shared deadline when retries are enabled, separate pool-wait and idle timeouts otherwise.
     *
     * @param ctx           the Vert.x test context
     * @param enableRetries {@code true} to send {@code x-route-retry-5xx: 2} with request #2
     *                      (shared request-wide deadline), {@code false} to send {@code 0} (no retries)
     */
    private void assertPoolWaitTimeout(TestContext ctx, boolean enableRetries) {
        final int timeoutMs = 1000;
        final long holdMs = 700; // backend releases req1's connection near the end of the timeout window

        // Backend: the FIRST connection receives a response after holdMs (freeing the sole pool
        // slot); every later connection is drained but never answered.
        Async backendReady = ctx.async();
        java.util.concurrent.atomic.AtomicReference<Runnable> dispatchSecond = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicLong firstConnectionAt = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong secondConnectionAt = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong secondCompletedAt = new java.util.concurrent.atomic.AtomicLong();
        AtomicInteger connectionCount = new AtomicInteger(0);
        io.vertx.core.net.NetServer backend = vertx.createNetServer();
        backend.connectHandler(socket -> {
            socket.handler(buf -> { /* drain incoming request bytes */ });
            if (connectionCount.incrementAndGet() == 1) {
                firstConnectionAt.set(System.nanoTime());
                vertx.setTimer(holdMs, id -> {
                    // Send a complete HTTP/1.1 response (Content-Length: 0 fully delimits it) and
                    // leave the socket open: with keepAlive=false the client closes the connection
                    // itself once the response is read, freeing the sole pool slot for req2. Closing
                    // the socket here instead would race the client's read and surface as a 500.
                    socket.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                });
                dispatchSecond.get().run();
            } else {
                secondConnectionAt.set(System.nanoTime());
            }
        });
        int[] backendPort = new int[1];
        backend.listen(0, ctx.asyncAssertSuccess(s -> {
            backendPort[0] = s.actualPort();
            backendReady.complete();
        }));
        backendReady.awaitSuccess(2000);

        Rule rule = buildRule(backendPort[0], timeoutMs);
        HttpClient httpClient = vertx.createHttpClient(rule.buildHttpClientOptions());
        Forwarder forwarder = buildForwarder(rule, httpClient);

        // Request #1 acquires the sole pool connection.
        java.util.concurrent.CountDownLatch req1Latch = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger capturedStatus1 = new AtomicInteger(-1);
        RoutingContext req1Context = buildRoutingContext(buildCapturingResponse(req1Latch, capturedStatus1));
        // Prepare both requests before timing. Dispatch req2 on the event loop as soon as req1
        // actually owns the sole connection, rather than relying on sleeps or main-thread wakeups.
        java.util.concurrent.CountDownLatch req2Latch = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger capturedStatus2 = new AtomicInteger(-1);
        DummyHttpServerResponse req2Response = buildCapturingResponse(req2Latch, capturedStatus2, secondCompletedAt);
        RoutingContext req2Context = buildRoutingContext(req2Response,
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", enableRetries ? "2" : "0"));
        dispatchSecond.set(() -> forwarder.handle(req2Context, Buffer.buffer(), null));
        forwarder.handle(req1Context);

        final boolean req1Done;
        final boolean req2Done;
        try {
            req2Done = req2Latch.await(3000, java.util.concurrent.TimeUnit.MILLISECONDS);
            req1Done = req1Latch.await(3000, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ctx.fail("Test interrupted");
            return;
        }
        long req2ElapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                secondCompletedAt.get() - firstConnectionAt.get());
        long idleMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                secondCompletedAt.get() - secondConnectionAt.get());

        ctx.assertTrue(req2Done, "Request #2 should have completed within 3000 ms");

        // req1 held the sole pool slot until the backend answered; once it completes the connection
        // is released so req2 can acquire it. We only assert that req1 finished (slot released) — its
        // exact status is irrelevant here and is not 200 in this harness: the streaming success path
        // touches DummyHttpServerResponse methods that FastFailHttpServerResponse leaves unimplemented,
        // so req1 deterministically ends as 500. That harness artifact does not affect req2's timing,
        // which is what this test verifies.
        ctx.assertTrue(req1Done,
                "Request #1 should have completed within 3000 ms so its pool slot is released, got status: "
                        + capturedStatus1.get());

        // req2 acquired a connection but the backend never answered. Either its shared deadline
        // (with retries) or its independent idle timeout (without retries) returns 502.
        ctx.assertEquals(
                StatusCode.BAD_GATEWAY.getStatusCode(),
                capturedStatus2.get(),
                "Expected 502 Bad Gateway for request #2 after acquiring its connection, got: "
                        + capturedStatus2.get()
        );

        // The whole request must stay within ~one configured timeout window. Without the fix the
        // idleTimeout would restart a full 1000 ms window after the ~700 ms pool wait (≈1700 ms).
        if (enableRetries) {
            ctx.assertTrue(req2ElapsedMs < 1500,
                    "Retry-enabled request must share one timeout across pool and response (~1700ms otherwise): " + req2ElapsedMs);
            ctx.assertTrue(idleMs < 800, "Pool waiting must leave substantially less than a full idle window: " + idleMs);
        } else {
            ctx.assertTrue(req2ElapsedMs >= 1500 && req2ElapsedMs < 2900,
                    "Without retries, pool waiting must not consume the separate 1000ms idle timeout: " + req2ElapsedMs);
            ctx.assertTrue(idleMs >= 850, "The ordinary request must still receive a full idle window: " + idleMs);
        }
        ctx.assertEquals(2, connectionCount.get(), "Both requests must actually acquire a connection");

        backend.close();
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
     * {@link DummyHttpServerRequest} wrapping the given response, without request headers.
     *
     * @param response the downstream response the forwarder writes to
     * @return the routing context for a GET of {@code REQUEST_URI}
     */
    private RoutingContext buildRoutingContext(DummyHttpServerResponse response) {
        return buildRoutingContext(response, new HeadersMultiMap());
    }

    /**
     * Builds a {@link RoutingContext} mock for an already ended (bodyless) GET of {@code REQUEST_URI}
     * with the given request headers, e.g. {@code x-route-retry-*} overrides.
     *
     * @param response the downstream response the forwarder writes to
     * @param headers  the request headers seen by the forwarder
     * @return the routing context
     */
    private RoutingContext buildRoutingContext(DummyHttpServerResponse response, MultiMap headers) {
        DummyHttpServerRequest fakeRequest = new DummyHttpServerRequest() {
            @Override public HttpMethod method()    { return HttpMethod.GET; }
            @Override public String uri()           { return REQUEST_URI; }
            @Override public String path()          { return REQUEST_URI; }
            @Override public MultiMap headers()     { return headers; }
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
     *
     * @param latch          counted down when the response is ended
     * @param capturedStatus receives the status code at the time the response is ended
     * @return the capturing response
     */
    private DummyHttpServerResponse buildCapturingResponse(
            java.util.concurrent.CountDownLatch latch, AtomicInteger capturedStatus) {
        return buildCapturingResponse(latch, capturedStatus, new java.util.concurrent.atomic.AtomicLong());
    }

    /**
     * Like {@link #buildCapturingResponse(java.util.concurrent.CountDownLatch, AtomicInteger)}, but
     * additionally records when the response was ended, for timing assertions.
     *
     * @param latch          counted down when the response is ended
     * @param capturedStatus receives the status code at the time the response is ended
     * @param completedAt    receives {@link System#nanoTime()} at the time the response is ended
     * @return the capturing response
     */
    private DummyHttpServerResponse buildCapturingResponse(
            java.util.concurrent.CountDownLatch latch, AtomicInteger capturedStatus,
            java.util.concurrent.atomic.AtomicLong completedAt) {
        return new DummyHttpServerResponse() {
            @Override
            public Future<Void> end() {
                capturedStatus.set(getStatusCode());
                completedAt.set(System.nanoTime());
                latch.countDown();
                return Future.succeededFuture();
            }

            @Override
            public Future<Void> end(String chunk) {
                capturedStatus.set(getStatusCode());
                completedAt.set(System.nanoTime());
                latch.countDown();
                return Future.succeededFuture();
            }

            @Override
            public Future<Void> end(Buffer chunk) {
                capturedStatus.set(getStatusCode());
                completedAt.set(System.nanoTime());
                latch.countDown();
                return Future.succeededFuture();
            }

            @Override
            public HttpServerResponse setChunked(boolean chunked) { return this; }

            @Override
            public boolean headWritten() { return false; }
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

            @Override
            public boolean headWritten() { return false; }
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

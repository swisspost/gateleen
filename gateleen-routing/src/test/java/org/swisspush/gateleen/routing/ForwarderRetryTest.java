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
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.logging.LogAppenderRepository;
import org.swisspush.gateleen.logging.LoggingResourceManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the automatic retry mechanism of {@link Forwarder} when an upstream target fails to
 * deliver a response (the {@code findme_49ot58h0inrnu3985h} bad-gateway path).
 *
 * <p>The number of forwarding attempts (initial try plus retries) is configured globally through the
 * system property {@link Forwarder#MAX_FORWARD_ATTEMPTS_PROPERTY}. Because {@link Forwarder} resolves
 * that property once per instance (in its constructor), every test sets the property <em>before</em>
 * building its forwarder and clears it again in {@link #tearDown(TestContext)}.
 *
 * <p>The tests exercise a range of retry values and drive them against a real backend that counts how
 * often it is contacted and can be told to fail (close the TCP connection before responding) a given
 * number of times before finally answering. Asserting on that hit count is the most direct proof of
 * how many attempts actually happened.
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li>default value {@code 1} &rarr; no retry (backend contacted exactly once),</li>
 *   <li>value {@code 3} with a permanently failing backend &rarr; exactly 3 attempts, then 502,</li>
 *   <li>value {@code 3} / {@code 5} with a backend that recovers &rarr; retries stop as soon as a
 *       successful response is received (200 forwarded downstream),</li>
 *   <li>non-idempotent method (POST) &rarr; never retried regardless of the configured value,</li>
 *   <li>streamed (non-buffered) body &rarr; never retried because it cannot be replayed,</li>
 *   <li>invalid / non-numeric property values &rarr; safely fall back to {@code 1} (no retry).</li>
 * </ul>
 */
@RunWith(VertxUnitRunner.class)
public class ForwarderRetryTest {

    private static final String LOGGING_URI = "/gateleen/server/admin/v1/logging";
    private static final String RULES_PATH = "/gateleen/server/admin/v1/routing/rules";
    private static final String USER_PROFILE_PATH = "/gateleen/server/users/v1/%s/profile";
    private static final String REQUEST_URI = "/test/resource";
    private static final long AWAIT_MS = 8000;

    private Vertx vertx;
    private HttpServer backend;
    private String previousPropertyValue;

    @Before
    public void setUp() {
        vertx = Vertx.vertx();
        // Remember and clear any externally configured value so tests start from the documented default.
        previousPropertyValue = System.getProperty(Forwarder.MAX_FORWARD_ATTEMPTS_PROPERTY);
        System.clearProperty(Forwarder.MAX_FORWARD_ATTEMPTS_PROPERTY);
    }

    @After
    public void tearDown(TestContext ctx) {
        // Restore the property so we never leak configuration into other test classes.
        if (previousPropertyValue == null) {
            System.clearProperty(Forwarder.MAX_FORWARD_ATTEMPTS_PROPERTY);
        } else {
            System.setProperty(Forwarder.MAX_FORWARD_ATTEMPTS_PROPERTY, previousPropertyValue);
        }
        vertx.close(ctx.asyncAssertSuccess());
    }

    // -------------------------------------------------------------------------
    // resolveMaxForwardAttempts() unit tests (property parsing / sanitizing)
    // -------------------------------------------------------------------------

    @Test
    public void testResolveMaxForwardAttempts_defaultWhenAbsent(TestContext ctx) {
        System.clearProperty(Forwarder.MAX_FORWARD_ATTEMPTS_PROPERTY);
        ctx.assertEquals(1, Forwarder.resolveMaxForwardAttempts());
    }

    @Test
    public void testResolveMaxForwardAttempts_validValue(TestContext ctx) {
        System.setProperty(Forwarder.MAX_FORWARD_ATTEMPTS_PROPERTY, "4");
        ctx.assertEquals(4, Forwarder.resolveMaxForwardAttempts());
    }

    @Test
    public void testResolveMaxForwardAttempts_valueWithSurroundingWhitespace(TestContext ctx) {
        System.setProperty(Forwarder.MAX_FORWARD_ATTEMPTS_PROPERTY, "  7  ");
        ctx.assertEquals(7, Forwarder.resolveMaxForwardAttempts());
    }

    @Test
    public void testResolveMaxForwardAttempts_zeroIsClampedToOne(TestContext ctx) {
        System.setProperty(Forwarder.MAX_FORWARD_ATTEMPTS_PROPERTY, "0");
        ctx.assertEquals(1, Forwarder.resolveMaxForwardAttempts());
    }

    @Test
    public void testResolveMaxForwardAttempts_negativeIsClampedToOne(TestContext ctx) {
        System.setProperty(Forwarder.MAX_FORWARD_ATTEMPTS_PROPERTY, "-5");
        ctx.assertEquals(1, Forwarder.resolveMaxForwardAttempts());
    }

    @Test
    public void testResolveMaxForwardAttempts_nonNumericFallsBackToOne(TestContext ctx) {
        System.setProperty(Forwarder.MAX_FORWARD_ATTEMPTS_PROPERTY, "not-a-number");
        ctx.assertEquals(1, Forwarder.resolveMaxForwardAttempts());
    }

    // -------------------------------------------------------------------------
    // End-to-end retry behaviour tests (different retry values)
    // -------------------------------------------------------------------------

    /**
     * Default configuration (property absent, budget = 1): a permanently failing backend must be
     * contacted exactly once and the client must receive a 502.
     */
    @Test
    public void testDefault_noRetry_backendContactedOnce(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int backendPort = startCountingBackend(ctx, hits, Integer.MAX_VALUE);

        int status = forwardBufferedGetAndAwaitStatus(ctx, backendPort);

        ctx.assertEquals(1, hits.get(), "With the default budget of 1, the backend must be contacted exactly once");
        ctx.assertEquals(StatusCode.BAD_GATEWAY.getStatusCode(), status, "Expected 502 after the single attempt failed");
    }

    /**
     * Budget = 3 against a backend that always fails: the backend must be contacted exactly 3 times
     * (1 initial + 2 retries) and the client must still ultimately receive a 502.
     */
    @Test
    public void testMaxAttempts3_backendAlwaysFails_contactedThreeTimes(TestContext ctx) {
        System.setProperty(Forwarder.MAX_FORWARD_ATTEMPTS_PROPERTY, "3");
        AtomicInteger hits = new AtomicInteger();
        int backendPort = startCountingBackend(ctx, hits, Integer.MAX_VALUE);

        int status = forwardBufferedGetAndAwaitStatus(ctx, backendPort);

        ctx.assertEquals(3, hits.get(), "Budget of 3 must lead to exactly 3 attempts against a permanently failing backend");
        ctx.assertEquals(StatusCode.BAD_GATEWAY.getStatusCode(), status, "Expected 502 after all 3 attempts failed");
    }

    /**
     * Budget = 3 against a backend that fails twice and then succeeds: the retry loop must stop as
     * soon as the 3rd attempt succeeds, so the backend is contacted exactly 3 times and the client
     * receives the successful 200 response.
     */
    @Test
    public void testMaxAttempts3_backendRecoversOnThirdTry_succeeds(TestContext ctx) {
        System.setProperty(Forwarder.MAX_FORWARD_ATTEMPTS_PROPERTY, "3");
        AtomicInteger hits = new AtomicInteger();
        int backendPort = startCountingBackend(ctx, hits, 2 /* fail the first two attempts */);

        int status = forwardBufferedGetAndAwaitStatus(ctx, backendPort);

        ctx.assertEquals(3, hits.get(), "Backend must be contacted 3 times: two failures plus the successful retry");
        ctx.assertEquals(StatusCode.OK.getStatusCode(), status, "Expected the recovered 200 response to be forwarded");
    }

    /**
     * Budget = 5 but the backend recovers already on the 2nd attempt: retrying must stop immediately
     * once a response is received, so only 2 contacts happen even though 5 would have been allowed.
     */
    @Test
    public void testMaxAttempts5_backendRecoversOnSecondTry_stopsEarly(TestContext ctx) {
        System.setProperty(Forwarder.MAX_FORWARD_ATTEMPTS_PROPERTY, "5");
        AtomicInteger hits = new AtomicInteger();
        int backendPort = startCountingBackend(ctx, hits, 1 /* fail only the first attempt */);

        int status = forwardBufferedGetAndAwaitStatus(ctx, backendPort);

        ctx.assertEquals(2, hits.get(), "Retrying must stop as soon as a response is received, even below the budget");
        ctx.assertEquals(StatusCode.OK.getStatusCode(), status, "Expected the recovered 200 response to be forwarded");
    }

    /**
     * Budget = 3 but the request method is POST (not idempotent): the request must never be retried,
     * so the always-failing backend is contacted exactly once and the client receives a 502.
     */
    @Test
    public void testNonIdempotentPost_isNeverRetried(TestContext ctx) {
        System.setProperty(Forwarder.MAX_FORWARD_ATTEMPTS_PROPERTY, "3");
        AtomicInteger hits = new AtomicInteger();
        int backendPort = startCountingBackend(ctx, hits, Integer.MAX_VALUE);

        int status = forwardAndAwaitStatus(ctx, backendPort, HttpMethod.POST, Buffer.buffer("payload"));

        ctx.assertEquals(1, hits.get(), "A non-idempotent POST must never be retried");
        ctx.assertEquals(StatusCode.BAD_GATEWAY.getStatusCode(), status, "Expected 502 without any retry");
    }

    /**
     * Budget = 3 but the request body is streamed (not buffered in memory): such a body cannot be
     * replayed, so the request must not be retried even for an idempotent GET.
     */
    @Test
    public void testStreamedBody_isNeverRetried(TestContext ctx) {
        System.setProperty(Forwarder.MAX_FORWARD_ATTEMPTS_PROPERTY, "3");
        AtomicInteger hits = new AtomicInteger();
        int backendPort = startCountingBackend(ctx, hits, Integer.MAX_VALUE);

        // Passing bodyData == null (via handle(RoutingContext)) means the body is treated as streamed.
        int status = forwardAndAwaitStatus(ctx, backendPort, HttpMethod.GET, null);

        ctx.assertEquals(1, hits.get(), "A streamed (non-replayable) body must never be retried");
        ctx.assertEquals(StatusCode.BAD_GATEWAY.getStatusCode(), status, "Expected 502 without any retry");
    }

    /**
     * A non-numeric property value must be ignored and fall back to the default budget of 1, i.e. no
     * retry happens.
     */
    @Test
    public void testInvalidPropertyValue_disablesRetry(TestContext ctx) {
        System.setProperty(Forwarder.MAX_FORWARD_ATTEMPTS_PROPERTY, "banana");
        AtomicInteger hits = new AtomicInteger();
        int backendPort = startCountingBackend(ctx, hits, Integer.MAX_VALUE);

        int status = forwardBufferedGetAndAwaitStatus(ctx, backendPort);

        ctx.assertEquals(1, hits.get(), "An invalid property value must behave like the default (no retry)");
        ctx.assertEquals(StatusCode.BAD_GATEWAY.getStatusCode(), status, "Expected 502 after the single attempt failed");
    }

    /**
     * Budget = 3 against a backend that accepts the connection but never answers, so every attempt can
     * only be ended by the upstream idle timeout. This is the scenario the request-wide deadline is
     * meant to bound: without it, each of the 3 attempts would arm its own full {@code timeout} idle
     * timer, so the caller could wait up to ~3 &times; {@code timeout}. With the shared deadline the
     * whole forwarding (all attempts together) must complete within roughly a single {@code timeout}.
     *
     * <p>We assert on the elapsed wall-clock time rather than only on the attempt count: the final
     * response must arrive within one end-to-end budget, not one budget per attempt.
     */
    /**
     * Budget = 3 against a backend whose first attempt fails fast (connection reset) so a retry is
     * actually triggered, and whose subsequent attempts hang forever, so each retry can only be ended
     * by the upstream idle timeout. This is the scenario the request-wide deadline is meant to bound:
     * without it, the retry would arm its own <em>full</em> {@code timeout} idle timer on top of the
     * first attempt, so the caller could wait up to ~N &times; {@code timeout}. With the shared
     * deadline the retry may only consume the time left of the single budget, so the whole forwarding
     * completes within roughly one {@code timeout}.
     *
     * <p>We assert on the elapsed wall-clock time rather than only on the attempt count: the final
     * response must arrive within one end-to-end budget, not one budget per attempt. We also assert
     * that a retry really happened, so the timing bound is exercised on a genuine retry path.
     */
    @Test
    public void testRetriesShareSingleTimeoutBudget(TestContext ctx) {
        System.setProperty(Forwarder.MAX_FORWARD_ATTEMPTS_PROPERTY, "3");
        final int timeoutMs = 1000;
        AtomicInteger hits = new AtomicInteger();
        // Fail the first attempt instantly, then hang every following attempt until its idle timeout.
        int backendPort = startFailFastThenHangingBackend(ctx, hits, 1);

        Rule rule = buildRule(backendPort);
        rule.setTimeout(timeoutMs);
        HttpClient httpClient = vertx.createHttpClient(rule.buildHttpClientOptions());
        Forwarder forwarder = buildForwarder(rule, httpClient);

        Async done = ctx.async();
        CapturingResponse response = new CapturingResponse(done);
        RoutingContext routingContext = buildRoutingContext(HttpMethod.GET, response);

        long startMs = System.currentTimeMillis();
        forwarder.handle(routingContext, Buffer.buffer("payload"), null);
        done.awaitSuccess(AWAIT_MS);
        long elapsedMs = System.currentTimeMillis() - startMs;

        ctx.assertTrue(hits.get() >= 2, "Expected at least one retry to be triggered, but backend saw " + hits.get() + " attempt(s)");
        ctx.assertTrue(elapsedMs < 2L * timeoutMs,
                "All retries must share a single timeout budget: elapsed=" + elapsedMs
                        + "ms must stay well under the per-attempt worst case of " + (3L * timeoutMs) + "ms");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Starts a backend that counts every request it receives. For the first {@code failFastTimes}
     * requests it closes the TCP connection immediately (which the forwarder observes as an upstream
     * response failure and, for an idempotent buffered request, retries). Every request after that is
     * left hanging with no response, so the forwarding attempt can only be terminated by the upstream
     * idle timeout.
     *
     * @return the actual listen port.
     */
    private int startFailFastThenHangingBackend(TestContext ctx, AtomicInteger hitCounter, int failFastTimes) {
        Async ready = ctx.async();
        int[] port = new int[1];
        backend = vertx.createHttpServer();
        backend.requestHandler(req -> {
            int attemptNr = hitCounter.incrementAndGet();
            if (attemptNr <= failFastTimes) {
                req.connection().close();
            } // else: deliberately never respond, forcing the idle timeout to end this attempt
        });
        backend.listen(0, ctx.asyncAssertSuccess(server -> {
            port[0] = server.actualPort();
            ready.complete();
        }));
        ready.awaitSuccess(AWAIT_MS);
        return port[0];
    }

    /**
     * Starts a backend that counts every request it receives. For the first {@code failTimes}
     * requests it closes the TCP connection before responding (which the forwarder observes as an
     * upstream response failure). From then on it answers with {@code 200 OK} and the body {@code ok}.
     *
     * @return the actual listen port.
     */
    private int startCountingBackend(TestContext ctx, AtomicInteger hitCounter, int failTimes) {
        Async ready = ctx.async();
        int[] port = new int[1];
        backend = vertx.createHttpServer();
        backend.requestHandler(req -> {
            int attemptNr = hitCounter.incrementAndGet();
            if (attemptNr <= failTimes) {
                req.connection().close();
            } else {
                req.response().setStatusCode(StatusCode.OK.getStatusCode()).end("ok");
            }
        });
        backend.listen(0, ctx.asyncAssertSuccess(server -> {
            port[0] = server.actualPort();
            ready.complete();
        }));
        ready.awaitSuccess(AWAIT_MS);
        return port[0];
    }

    /** Convenience for the common case: an idempotent GET with a buffered body. */
    private int forwardBufferedGetAndAwaitStatus(TestContext ctx, int backendPort) {
        return forwardAndAwaitStatus(ctx, backendPort, HttpMethod.GET, Buffer.buffer("payload"));
    }

    /**
     * Forwards a single request through a freshly built {@link Forwarder} (which snapshots the retry
     * property in its constructor) and blocks until the downstream response is ended.
     *
     * @param method   the request method to use.
     * @param bodyData the buffered request body, or {@code null} to simulate a streamed body.
     * @return the status code captured on the downstream response.
     */
    private int forwardAndAwaitStatus(TestContext ctx, int backendPort, HttpMethod method, Buffer bodyData) {
        Rule rule = buildRule(backendPort);
        HttpClient httpClient = vertx.createHttpClient(rule.buildHttpClientOptions());
        Forwarder forwarder = buildForwarder(rule, httpClient);

        Async done = ctx.async();
        CapturingResponse response = new CapturingResponse(done);
        RoutingContext routingContext = buildRoutingContext(method, response);

        forwarder.handle(routingContext, bodyData, null);

        done.awaitSuccess(AWAIT_MS);
        return response.status.get();
    }

    private Forwarder buildForwarder(Rule rule, HttpClient httpClient) {
        MockResourceStorage storage = new MockResourceStorage(ImmutableMap.of(RULES_PATH, "{}"));
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

    private RoutingContext buildRoutingContext(HttpMethod method, HttpServerResponse response) {
        DummyHttpServerRequest fakeRequest = new DummyHttpServerRequest() {
            @Override public HttpMethod method() { return method; }
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
        when(routingCtx.request()).thenReturn(fakeRequest);
        return routingCtx;
    }

    private static Rule buildRule(int port) {
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
        return rule;
    }

    /**
     * Downstream response that records the final status code and completes the given {@link Async}
     * exactly once, on the first {@code end(...)} call.
     */
    private static class CapturingResponse extends DummyHttpServerResponse {

        private final AtomicInteger status = new AtomicInteger(-1);
        private final Buffer body = Buffer.buffer();
        private final Async async;
        private boolean completed = false;

        private CapturingResponse(Async async) {
            this.async = async;
        }

        private void finish() {
            status.set(getStatusCode());
            if (!completed) {
                completed = true;
                async.complete();
            }
        }

        @Override
        public Future<Void> end() {
            finish();
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> end(String chunk) {
            finish();
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> end(Buffer chunk) {
            body.appendBuffer(chunk);
            finish();
            return Future.succeededFuture();
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
        public HttpServerResponse setChunked(boolean chunked) { return this; }

        @Override
        public HttpServerResponse setWriteQueueMaxSize(int maxSize) { return this; }

        @Override
        public boolean writeQueueFull() { return false; }

        @Override
        public HttpServerResponse drainHandler(Handler<Void> handler) { return this; }

        @Override
        public HttpServerResponse exceptionHandler(Handler<Throwable> handler) { return this; }

        @Override
        public boolean headWritten() { return false; }
    }
}

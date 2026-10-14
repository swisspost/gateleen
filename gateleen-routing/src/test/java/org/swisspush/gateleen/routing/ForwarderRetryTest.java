package org.swisspush.gateleen.routing;

import com.google.common.collect.ImmutableMap;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.Router;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.core.config.Configurator;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.http.DummyHttpServerResponse;
import org.swisspush.gateleen.core.http.HeaderFunctions;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.logging.LogAppenderRepository;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.logging.LoggingResource;
import org.swisspush.gateleen.logging.LoggingHandler;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.mockito.MockedConstruction;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;

/**
 * Integration coverage for counted retries, replay safety, shared deadlines and terminal
 * logging/metrics against real HTTP backends. Policy values count additional retries, whereas
 * backend hit counts include the initial attempt. System properties are set before constructing
 * each forwarder (which snapshots them) and restored in {@link #tearDown(TestContext)}.
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
    private String previousStatusPropertyValue;
    private String previousCeilingPropertyValue;
    private String previousBodyLimitPropertyValue;
    private String previousExcludePropertyValue;

    /**
     * Creates a Vert.x instance and clears retry-related system properties after saving their
     * previous values so each test constructs a forwarder from a known default policy.
     */
    @Before
    public void setUp() {
        vertx = Vertx.vertx();
        // Remember and clear any externally configured value so tests start from the documented default.
        previousStatusPropertyValue = System.getProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY);
        System.clearProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY);
        previousCeilingPropertyValue = System.getProperty(ForwarderRetryPolicy.MAX_RETRIES_PROPERTY);
        System.clearProperty(ForwarderRetryPolicy.MAX_RETRIES_PROPERTY);
        previousBodyLimitPropertyValue = System.getProperty(ForwarderRetryPolicy.MAX_RETRY_BODY_BYTES_PROPERTY);
        System.clearProperty(ForwarderRetryPolicy.MAX_RETRY_BODY_BYTES_PROPERTY);
        previousExcludePropertyValue = System.getProperty(ForwarderRetryPolicy.RETRY_EXCLUDE_PATTERN_PROPERTY);
        System.clearProperty(ForwarderRetryPolicy.RETRY_EXCLUDE_PATTERN_PROPERTY);
    }

    /**
     * Restores retry-related system properties and closes Vert.x after each test.
     *
     * @param ctx Vert.x unit context used to await successful shutdown
     */
    @After
    public void tearDown(TestContext ctx) {
        // Restore the property so we never leak configuration into other test classes.
        if (previousStatusPropertyValue == null) {
            System.clearProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY);
        } else {
            System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, previousStatusPropertyValue);
        }
        if (previousCeilingPropertyValue == null) {
            System.clearProperty(ForwarderRetryPolicy.MAX_RETRIES_PROPERTY);
        } else {
            System.setProperty(ForwarderRetryPolicy.MAX_RETRIES_PROPERTY, previousCeilingPropertyValue);
        }
        if (previousBodyLimitPropertyValue == null) {
            System.clearProperty(ForwarderRetryPolicy.MAX_RETRY_BODY_BYTES_PROPERTY);
        } else {
            System.setProperty(ForwarderRetryPolicy.MAX_RETRY_BODY_BYTES_PROPERTY, previousBodyLimitPropertyValue);
        }
        if (previousExcludePropertyValue == null) {
            System.clearProperty(ForwarderRetryPolicy.RETRY_EXCLUDE_PATTERN_PROPERTY);
        } else {
            System.setProperty(ForwarderRetryPolicy.RETRY_EXCLUDE_PATTERN_PROPERTY, previousExcludePropertyValue);
        }
        vertx.close(ctx.asyncAssertSuccess());
    }

    // -------------------------------------------------------------------------
    // Retry exclusion (retryExcludePattern)
    // -------------------------------------------------------------------------

    /**
     * A rule matching the exclusion by its URL pattern ignores the static 502 policy: a permanently
     * failing backend is contacted once.
     */
    @Test
    public void testExcludedRule_staticPolicyIgnored_contactedOnce(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"502\":2,\"upstreamFailure\":2}");
        System.setProperty(ForwarderRetryPolicy.RETRY_EXCLUDE_PATTERN_PROPERTY, ".*/TEST/.*");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 502);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap());
        ctx.assertEquals(1, hits.get(), "The exclusion (matched case-insensitively) must disable the static retries");
        ctx.assertEquals(502, response.status.get());
    }

    /**
     * The exclusion also disables the static upstreamFailure policy.
     */
    @Test
    public void testExcludedRule_upstreamFailureNotRetried(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"upstreamFailure\":2}");
        System.setProperty(ForwarderRetryPolicy.RETRY_EXCLUDE_PATTERN_PROPERTY, ".*/test/.*");
        AtomicInteger hits = new AtomicInteger();
        int backendPort = startCountingBackend(ctx, hits, Integer.MAX_VALUE);

        int status = forwardBufferedGetAndAwaitStatus(ctx, backendPort);

        ctx.assertEquals(1, hits.get());
        ctx.assertEquals(StatusCode.BAD_GATEWAY.getStatusCode(), status);
    }

    /**
     * A rule matching the exclusion by its target URL only is excluded as well.
     */
    @Test
    public void testExcludedRule_matchedByTargetUrl(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"502\":2}");
        System.setProperty(ForwarderRetryPolicy.RETRY_EXCLUDE_PATTERN_PROPERTY, "http://localhost:\\d+/test/resource");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 502);
        forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"), MultiMap.caseInsensitiveMultiMap());
        ctx.assertEquals(1, hits.get());
    }

    /**
     * Retry headers still apply to an excluded rule: the client explicitly asks for the retries.
     */
    @Test
    public void testExcludedRule_retryHeaderStillApplies(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"502\":2}");
        System.setProperty(ForwarderRetryPolicy.RETRY_EXCLUDE_PATTERN_PROPERTY, ".*/test/.*");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 502);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-502", "3"));
        ctx.assertEquals(4, hits.get());
        ctx.assertEquals(502, response.status.get());
    }

    /**
     * A rule not matching the exclusion keeps the static policy.
     */
    @Test
    public void testNotExcludedRule_staticPolicyApplies(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"502\":2}");
        System.setProperty(ForwarderRetryPolicy.RETRY_EXCLUDE_PATTERN_PROPERTY, ".*houston.*");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 502);
        forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"), MultiMap.caseInsensitiveMultiMap());
        ctx.assertEquals(3, hits.get());
    }

    /**
     * Excludes only the outer local rule of a local-to-url chain and asserts the inner rule, which
     * calls the backend, still applies the static policy (3 attempts): the exclusion must match the
     * final rule, as documented.
     */
    @Test
    public void testExcludedLocalRule_doesNotExcludeInnerRuleOfChain(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"503\":2}");
        System.setProperty(ForwarderRetryPolicy.RETRY_EXCLUDE_PATTERN_PROPERTY, "/houston/.*");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503);
        CapturingResponse response = forwardThroughLocalChain(ctx, port, "/houston/(.*)", "/inner/(.*)");
        ctx.assertEquals(503, response.status.get());
        ctx.assertEquals(3, hits.get());
    }

    /**
     * Excludes the inner url rule of a local-to-url chain and asserts the chain makes a single
     * attempt.
     */
    @Test
    public void testExcludedInnerRule_excludesChain(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"503\":2}");
        System.setProperty(ForwarderRetryPolicy.RETRY_EXCLUDE_PATTERN_PROPERTY, "/houston/.*");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503);
        CapturingResponse response = forwardThroughLocalChain(ctx, port, "/outer/(.*)", "/houston/(.*)");
        ctx.assertEquals(503, response.status.get());
        ctx.assertEquals(1, hits.get());
    }

    /**
     * Forwards a GET through a local outer rule to an inner url rule targeting the backend, like a
     * routing rule with a {@code path} that is routed again by a rule with a {@code url}.
     *
     * @param ctx             Vert.x unit context
     * @param port            backend port
     * @param outerUrlPattern URL pattern of the local outer rule
     * @param innerUrlPattern URL pattern of the inner url rule
     * @return the response seen by the client of the outer rule
     */
    private CapturingResponse forwardThroughLocalChain(TestContext ctx, int port, String outerUrlPattern,
                                                       String innerUrlPattern) {
        Rule innerRule = buildRule(port);
        innerRule.setUrlPattern(innerUrlPattern);
        Forwarder inner = buildForwarder(innerRule, vertx.createHttpClient(innerRule.buildHttpClientOptions()));
        Rule outerRule = buildRule(port);
        outerRule.setScheme("local");
        outerRule.setUrlPattern(outerUrlPattern);
        Forwarder outer = buildForwarder(outerRule, newSelfClient(inner));
        return forwardAndAwaitResponse(ctx, outer, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap());
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
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"upstreamFailure\":2}");
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
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"upstreamFailure\":2}");
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
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"upstreamFailure\":4}");
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
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"upstreamFailure\":2}");
        AtomicInteger hits = new AtomicInteger();
        int backendPort = startCountingBackend(ctx, hits, Integer.MAX_VALUE);

        int status = forwardAndAwaitStatus(ctx, backendPort, HttpMethod.POST, Buffer.buffer("payload"));

        ctx.assertEquals(1, hits.get(), "A non-idempotent POST must never be retried");
        ctx.assertEquals(StatusCode.BAD_GATEWAY.getStatusCode(), status, "Expected 502 without any retry");
    }

    /**
     * Budget = 3 and the request comes through the router without a pre-buffered body
     * ({@code bodyData == null}). Because a retry policy applies to this idempotent GET, the
     * forwarder buffers the (empty) body itself and replays it.
     */
    @Test
    public void testRoutedBodylessGet_isRetried(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"upstreamFailure\":2}");
        AtomicInteger hits = new AtomicInteger();
        int backendPort = startCountingBackend(ctx, hits, Integer.MAX_VALUE);

        int status = forwardAndAwaitStatus(ctx, backendPort, HttpMethod.GET, null);

        ctx.assertEquals(3, hits.get(), "A routed request must be buffered and retried when a policy applies");
        ctx.assertEquals(StatusCode.BAD_GATEWAY.getStatusCode(), status, "Expected 502 after all 3 attempts failed");
    }

    /** Counterpart without any retry configuration: a routed request is attempted exactly once. */
    @Test
    public void testRoutedBodylessGet_withoutPolicy_isNotRetried(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int backendPort = startCountingBackend(ctx, hits, Integer.MAX_VALUE);

        int status = forwardAndAwaitStatus(ctx, backendPort, HttpMethod.GET, null);

        ctx.assertEquals(1, hits.get(), "Without a retry policy a routed request must never be retried");
        ctx.assertEquals(StatusCode.BAD_GATEWAY.getStatusCode(), status, "Expected 502 without any retry");
    }

    /**
     * A non-numeric property value must be ignored and fall back to the default budget of 1, i.e. no
     * retry happens.
     */
    @Test
    public void testInvalidPropertyValue_disablesRetry(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"upstreamFailure\":\"banana\"}");
        AtomicInteger hits = new AtomicInteger();
        int backendPort = startCountingBackend(ctx, hits, Integer.MAX_VALUE);

        int status = forwardBufferedGetAndAwaitStatus(ctx, backendPort);

        ctx.assertEquals(1, hits.get(), "An invalid property value must behave like the default (no retry)");
        ctx.assertEquals(StatusCode.BAD_GATEWAY.getStatusCode(), status, "Expected 502 after the single attempt failed");
    }

    /**
     * The first attempt consumes 750ms before losing its connection. The retry then hangs.
     * The assertions verify two hits, one 502 completion and elapsed time under 1900ms, proving
     * retries share one request-wide timeout instead of getting a fresh 1200ms per attempt.
     */
    @Test
    public void testRetriesShareSingleTimeoutBudget(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"upstreamFailure\":2}");
        final int timeoutMs = 1200;
        AtomicInteger hits = new AtomicInteger();
        int backendPort = startDelayedFailureBackend(ctx, hits, false);

        Rule rule = buildRule(backendPort);
        rule.setTimeout(timeoutMs);
        HttpClient httpClient = vertx.createHttpClient(rule.buildHttpClientOptions());
        Forwarder forwarder = buildForwarder(rule, httpClient);

        Async done = ctx.async();
        CapturingResponse response = new CapturingResponse(done);
        RoutingContext routingContext = buildRoutingContext(HttpMethod.GET, response);

        long started = System.nanoTime();
        forwarder.handle(routingContext, Buffer.buffer("payload"), null);
        done.awaitSuccess(AWAIT_MS);
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(response.completedAtNanos - started);

        ctx.assertEquals(2, hits.get());
        ctx.assertEquals(502, response.status.get());
        ctx.assertEquals(1, response.endCalls.get());
        ctx.assertTrue(elapsedMs >= 1000, "Must wait for the remaining timeout, not fail immediately: " + elapsedMs);
        ctx.assertTrue(elapsedMs < 1900,
                "A retry must not get a fresh 1200ms after the first 750ms (>= 1950ms): " + elapsedMs);
    }

    // -------------------------------------------------------------------------
    // Per-request x-route-retry-* header tests
    // -------------------------------------------------------------------------

    /**
     * Sends x-route-retry-4xx:3 to a backend returning 400 and asserts four total hits, the final
     * 400 response, last-attempt body and attempt header are forwarded.
     */
    @Test
    public void testStatusClassHeader_retriesThenReturnsFinalResponse(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 400);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-4xx", "3"));
        ctx.assertEquals(4, hits.get());
        ctx.assertEquals(400, response.status.get());
        ctx.assertEquals("response-4", response.body.toString());
        ctx.assertEquals("4", response.headers().get("x-backend-attempt"));
    }

    /**
     * Configures static 503:1 but sends x-route-retry-503:3; asserts the exact per-request header
     * overrides static policy and yields four total 503 hits.
     */
    @Test
    public void testExactStatusHeader_overridesStaticPolicy(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"503\":1}");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-503", "3"));
        ctx.assertEquals(4, hits.get());
        ctx.assertEquals(503, response.status.get());
    }

    /**
     * Sends both x-route-retry-4xx:3 and exact x-route-retry-404:0 to a 404 backend and asserts the
     * exact zero override prevents retries, leaving one hit and a 404 response.
     */
    @Test
    public void testExactZeroHeader_overridesClassHeader(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 404);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-4xx", "3").set("x-route-retry-404", "0"));
        ctx.assertEquals(1, hits.get());
        ctx.assertEquals(404, response.status.get());
    }

    /**
     * Allows 5xx retries while the backend returns 503 then 200; asserts retrying stops on the
     * second successful hit and ignores an unrelated 2xx retry header.
     */
    @Test
    public void testStatusHeader_stopsOnSuccess(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503, 200);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("X-ROUTE-RETRY-5XX", "5").set("x-route-retry-2xx", "5"));
        ctx.assertEquals(2, hits.get());
        ctx.assertEquals(200, response.status.get());
        ctx.assertEquals("response-2", response.body.toString());
    }

    /**
     * Uses separate 4xx and 5xx header budgets against 400 then 503 responses and asserts the retry
     * counter is shared, producing three total attempts and the final 503.
     */
    @Test
    public void testChangingStatus_sharesRetryCounter(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 400, 503);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-4xx", "2").set("x-route-retry-5xx", "2"));
        ctx.assertEquals(3, hits.get());
        ctx.assertEquals(503, response.status.get());
    }

    /**
     * Configures only upstreamFailure plus a 4xx header against a received 503 and asserts a normal
     * HTTP error does not use the no-response policy, so only one 503 hit occurs.
     */
    @Test
    public void testUnmatchedError_isNotRetriedByUpstreamFailurePolicy(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"upstreamFailure\":4}");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-4xx", "3"));
        ctx.assertEquals(1, hits.get());
        ctx.assertEquals(503, response.status.get());
    }

    /**
     * Sends invalid exact x-route-retry-503 with a valid class x-route-retry-5xx and asserts the
     * invalid exact header suppresses class fallback, leaving one 503 hit.
     */
    @Test
    public void testInvalidExactHeader_doesNotFallBackToClass(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-503", "invalid").set("x-route-retry-5xx", "3"));
        ctx.assertEquals(1, hits.get());
        ctx.assertEquals(503, response.status.get());
    }

    /**
     * Sends a POST with x-route-retry-5xx to a 503 backend and asserts non-idempotent methods are
     * not replayed even when headers request retries.
     */
    @Test
    public void testHeaderRetry_nonIdempotentMethodIsNotReplayed(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.POST, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", "3"));
        ctx.assertEquals(1, hits.get());
        ctx.assertEquals(503, response.status.get());
    }

    /**
     * Sends a routed GET without pre-buffered body plus x-route-retry-5xx:3 and asserts the
     * forwarder buffers/replays it for four total 503 hits.
     */
    @Test
    public void testHeaderRetry_routedBodylessGetIsRetried(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, null,
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", "3"));
        ctx.assertEquals(4, hits.get());
        ctx.assertEquals(503, response.status.get());
    }

    /**
     * Counterpart without retry header or static policy: a routed 503 is returned at once with one
     * backend hit, proving bodyless routed requests are only buffered when retries are enabled.
     */
    @Test
    public void testRoutedBodylessGet_withoutRetryHeader_isNotRetried(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, null,
                MultiMap.caseInsensitiveMultiMap());
        ctx.assertEquals(1, hits.get());
        ctx.assertEquals(503, response.status.get());
    }

    /**
     * Uses x-route-retry-502:2 for a backend that closes connections and asserts no-response
     * failures map to 502 headers, giving three hits and a final 502.
     */
    @Test
    public void testHeaderRetry_upstreamFailureUses502Pattern(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startCountingBackend(ctx, hits, Integer.MAX_VALUE);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-502", "2"));
        ctx.assertEquals(3, hits.get());
        ctx.assertEquals(502, response.status.get());
    }

    /**
     * Configures upstreamFailure:4 but sends x-route-retry-5xx:0 and asserts the header disables
     * configured no-response retries, leaving one hit and 502.
     */
    @Test
    public void testHeaderZero_disablesConfiguredUpstreamFailureRetries(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"upstreamFailure\":4}");
        AtomicInteger hits = new AtomicInteger();
        int port = startCountingBackend(ctx, hits, Integer.MAX_VALUE);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", "0"));
        ctx.assertEquals(1, hits.get());
        ctx.assertEquals(502, response.status.get());
    }

    /**
     * Retries a keep-alive PUT after a 503 and asserts the drained first response permits connection
     * reuse: two hits, one connection, final 200 and second-attempt body.
     */
    @Test
    public void testHeaderRetry_reusesConnectionAfterDrainingResponse(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        AtomicInteger connections = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, connections, 503, 200);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.PUT, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", "2"), true);
        ctx.assertEquals(2, hits.get());
        ctx.assertEquals(1, connections.get());
        ctx.assertEquals(200, response.status.get());
        ctx.assertEquals("response-2", response.body.toString());
    }

    /**
     * Sends header retry budget 100 with x-timeout 1200 to a delayed failing backend and asserts the
     * shared deadline bounds retries to two hits, one 502 completion and under-1900ms duration.
     */
    @Test
    public void testHeaderRetry_requestWideTimeoutStillBoundsAttempts(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startDelayedFailureBackend(ctx, hits, true);
        Rule rule = buildRule(port);
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
        Async done = ctx.async();
        CapturingResponse response = new CapturingResponse(done);
        RoutingContext routingContext = buildRoutingContext(HttpMethod.GET, response,
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", "100").set("x-timeout", "1200"));
        long started = System.nanoTime();
        forwarder.handle(routingContext, Buffer.buffer("payload"), null);
        done.awaitSuccess(AWAIT_MS);
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(response.completedAtNanos - started);
        ctx.assertEquals(2, hits.get());
        ctx.assertEquals(502, response.status.get());
        ctx.assertEquals(1, response.endCalls.get());
        ctx.assertTrue(elapsedMs >= 1000, "Must wait for the remaining timeout: " + elapsedMs);
        ctx.assertTrue(elapsedMs < 1900, "Header retries must not restart the 1200ms timeout (>= 1950ms): " + elapsedMs);
    }

    /**
     * Makes the retry succeed only after the shared x-timeout has expired and asserts the caller gets
     * one 502 completion despite the late backend 200, proving late success cannot win.
     */
    @Test
    public void testRetryCannotAcceptSuccessBeyondSharedTimeout(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        Async ready = ctx.async();
        Async lateResponse = ctx.async();
        int[] port = new int[1];
        backend = vertx.createHttpServer().requestHandler(req -> {
            int attempt = hits.incrementAndGet();
            req.bodyHandler(body -> {
                ctx.assertEquals("payload", body.toString());
                vertx.setTimer(attempt == 1 ? 750 : 800, ignored -> {
                    if (attempt == 1) {
                        req.response().setStatusCode(503).end("retry");
                    } else {
                        // This would succeed within a fresh timeout, but not the time remaining.
                        req.response().end("too late").onComplete(result -> lateResponse.complete());
                    }
                });
            });
        });
        backend.listen(0, ctx.asyncAssertSuccess(server -> {
            port[0] = server.actualPort();
            ready.complete();
        }));
        ready.awaitSuccess(AWAIT_MS);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port[0], HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", "3").set("x-timeout", "1200"));
        ctx.assertEquals(2, hits.get());
        ctx.assertEquals(502, response.status.get(), "The delayed 200 must not receive a fresh timeout");
        lateResponse.awaitSuccess(AWAIT_MS);
        ctx.assertEquals(502, response.status.get());
        ctx.assertEquals(1, response.endCalls.get(), "A late backend response must not complete the caller twice");
    }

    /**
     * Makes the first 503 consume most of x-timeout but the retry succeed within the remaining time;
     * asserts two hits, final 200 body, one completion and cancellation of the deadline timer.
     */
    @Test
    public void testRetrySucceedsWithinRemainingBudget(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        Async ready = ctx.async();
        int[] port = new int[1];
        backend = vertx.createHttpServer().requestHandler(req -> {
            int attempt = hits.incrementAndGet();
            req.bodyHandler(body -> {
                ctx.assertEquals("payload", body.toString());
                vertx.setTimer(attempt == 1 ? 750 : 100, ignored ->
                        req.response().setStatusCode(attempt == 1 ? 503 : 200).end("response-" + attempt));
            });
        });
        backend.listen(0, ctx.asyncAssertSuccess(server -> {
            port[0] = server.actualPort();
            ready.complete();
        }));
        ready.awaitSuccess(AWAIT_MS);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port[0], HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", "3").set("x-timeout", "1200"));
        ctx.assertEquals(2, hits.get());
        ctx.assertEquals(200, response.status.get());
        ctx.assertEquals("response-2", response.body.toString());
        Async pastDeadline = ctx.async();
        vertx.setTimer(600, ignored -> pastDeadline.complete());
        pastDeadline.awaitSuccess(AWAIT_MS);
        ctx.assertEquals(1, response.endCalls.get(), "The deadline must be cancelled after success");
        ctx.assertEquals(200, response.getStatusCode());
    }

    /**
     * Starts a slow chunked 503 response under a 600ms rule timeout and asserts draining is bounded:
     * no body leaks, no extra retry starts, metrics stop once and a single 502 is returned.
     */
    @Test
    public void testRetryResponseDrainCannotOutliveDeadline(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        Async closed = ctx.async();
        int port = startProgressiveBackend(ctx, hits, 503, closed);
        Rule rule = buildRule(port);
        rule.setKeepAlive(true);
        rule.setTimeout(600);
        rule.setMetricName("retry-deadline");
        MonitoringHandler monitoring = mock(MonitoringHandler.class);
        when(monitoring.startRequestMetricTracking(anyString(), eq(REQUEST_URI))).thenReturn(123L);
        MockResourceStorage storage = new MockResourceStorage(ImmutableMap.of(RULES_PATH, "{}"));
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()),
                new LoggingResourceManager(vertx, storage, LOGGING_URI), mock(LogAppenderRepository.class), monitoring);
        Async done = ctx.async();
        CapturingResponse response = new CapturingResponse(done);
        RoutingContext routingContext = buildRoutingContext(HttpMethod.GET, response,
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-503", "2"));
        long started = System.nanoTime();
        forwarder.handle(routingContext, Buffer.buffer("payload"), null);
        done.awaitSuccess(AWAIT_MS);
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(response.completedAtNanos - started);
        closed.awaitSuccess(AWAIT_MS);
        ctx.assertEquals(502, response.status.get());
        ctx.assertEquals(0, response.body.length(), "Drained error payload must not leak downstream");
        ctx.assertEquals(1, hits.get(), "Expiry while draining must not start another attempt");
        ctx.assertEquals(1, response.endCalls.get());
        verify(monitoring, times(1)).startRequestMetricTracking("retry-deadline", REQUEST_URI);
        verify(monitoring, times(1)).stopRequestMetricTracking("retry-deadline", 123L, REQUEST_URI);
        ctx.assertTrue(elapsedMs >= 450 && elapsedMs < 1500, "Drain must be bounded by the 600ms deadline: " + elapsedMs);
    }

    /**
     * Finishes draining a retryable 503 with almost no timeout budget left and asserts the forwarder
     * checks the shared deadline again, making no second hit and returning one empty 502.
     */
    @Test
    public void testRetryBudgetIsCheckedAgainAfterDraining(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        Async ready = ctx.async();
        Async drained = ctx.async();
        int[] port = new int[1];
        backend = vertx.createHttpServer().requestHandler(req -> {
            hits.incrementAndGet();
            req.bodyHandler(body -> {
                ctx.assertEquals("payload", body.toString());
                req.response().setStatusCode(503).setChunked(true).write("discard");
                // The response head allowed a retry, but finishing the drain leaves <50ms.
                vertx.setTimer(560, ignored -> req.response().end().onComplete(result -> drained.complete()));
            });
        });
        backend.listen(0, ctx.asyncAssertSuccess(server -> {
            port[0] = server.actualPort();
            ready.complete();
        }));
        ready.awaitSuccess(AWAIT_MS);
        Rule rule = buildRule(port[0]);
        rule.setTimeout(600);
        rule.setKeepAlive(true);
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
        Async done = ctx.async();
        CapturingResponse response = new CapturingResponse(done);
        List<String> logs = captureForwarderLogs(() -> {
            forwarder.handle(buildRoutingContext(HttpMethod.GET, response,
                    MultiMap.caseInsensitiveMultiMap().set("x-route-retry-503", "2")), Buffer.buffer("payload"), null);
            done.awaitSuccess(AWAIT_MS);
            return null;
        });
        drained.awaitSuccess(AWAIT_MS);
        ctx.assertEquals(1, hits.get(), "Do not launch a near-zero-budget retry after draining");
        ctx.assertEquals(502, response.status.get());
        ctx.assertEquals(0, response.body.length());
        ctx.assertEquals(1, response.endCalls.get());
        ctx.assertTrue(logs.contains("ERROR http://localhost:" + port[0] + REQUEST_URI
                + " Insufficient retry budget after draining HTTP 503"), "Budget re-check must end the request: " + logs);
    }

    /**
     * Streams a progressive 200 that outlives the retry deadline and asserts already-sent success
     * headers are not changed to 502, the body fails closed, and no retry occurs.
     */
    @Test
    public void testProgressiveSuccessfulResponseCannotOutliveRetryDeadline(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        Async closed = ctx.async();
        int port = startProgressiveBackend(ctx, hits, 200, closed);
        Rule rule = buildRule(port);
        rule.setKeepAlive(true);
        rule.setTimeout(600);
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"5xx\":2}");
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
        int proxyPort = startForwardingServer(ctx, forwarder, true);
        Async done = ctx.async();
        java.util.concurrent.atomic.AtomicLong failedAt = new java.util.concurrent.atomic.AtomicLong();
        long started = System.nanoTime();
        vertx.createHttpClient().request(HttpMethod.GET, proxyPort, "localhost", REQUEST_URI)
                .compose(req -> req.send(Buffer.buffer("payload"))).onComplete(ctx.asyncAssertSuccess(response -> {
                    ctx.assertEquals(200, response.statusCode(), "Already sent headers cannot become 502");
                    response.body().onComplete(result -> {
                        failedAt.set(System.nanoTime());
                        ctx.assertTrue(result.failed(), "Partial response must be closed, not completed successfully");
                        done.complete();
                    });
                }));
        done.awaitSuccess(AWAIT_MS);
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(failedAt.get() - started);
        closed.awaitSuccess(AWAIT_MS);
        ctx.assertEquals(1, hits.get(), "A partially delivered success must never be retried");
        ctx.assertTrue(elapsedMs >= 450 && elapsedMs < 1500, "Progress must not extend the 600ms deadline: " + elapsedMs);
    }

    /**
     * Verifies a normal buffered GET without retry policy keeps the rule idle-timeout semantics and
     * receives the full progressive 200 response in one backend hit.
     */
    @Test
    public void testOrdinaryProgressiveResponseRetainsIdleTimeout(TestContext ctx) {
        assertProgressiveResponseSucceeds(ctx, HttpMethod.GET, true, MultiMap.caseInsensitiveMultiMap());
    }

    /**
     * Configures retries but ceiling zero and asserts retry-disabled progressive GET traffic still
     * uses idle timeout, completing the full 200 stream in one hit.
     */
    @Test
    public void testZeroCeilingRetainsIdleTimeout(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"5xx\":2}");
        System.setProperty(ForwarderRetryPolicy.MAX_RETRIES_PROPERTY, "0");
        assertProgressiveResponseSucceeds(ctx, HttpMethod.GET, true, MultiMap.caseInsensitiveMultiMap());
    }

    /**
     * Uses a zero x-route-retry-5xx override against static retry policy and asserts the disabled
     * policy retains idle timeout behaviour for a full progressive 200 response.
     */
    @Test
    public void testZeroHeaderOverrideRetainsIdleTimeout(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"5xx\":2,\"upstreamFailure\":2}");
        assertProgressiveResponseSucceeds(ctx, HttpMethod.GET, true,
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", "0"));
    }

    /**
     * Configures retries for a buffered POST and asserts the non-idempotent request is streamed with
     * idle timeout semantics, completing one progressive 200 hit.
     */
    @Test
    public void testNonIdempotentRequestRetainsIdleTimeout(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"5xx\":2}");
        assertProgressiveResponseSucceeds(ctx, HttpMethod.POST, true, MultiMap.caseInsensitiveMultiMap());
    }

    /**
     * Sends a real streamed POST with retry policy and asserts it remains non-replayable, preserving
     * idle timeout and receiving the full progressive 200 response in one hit.
     */
    @Test
    public void testRealStreamedRequestRetainsIdleTimeout(TestContext ctx) {
        // A retry policy exists, but POST is not replayable, so the router keeps streaming the body.
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"5xx\":2}");
        assertProgressiveResponseSucceeds(ctx, HttpMethod.POST, false, MultiMap.caseInsensitiveMultiMap());
    }

    /**
     * Routes a streamed PUT without any effective retry policy and asserts it is not buffered for
     * replay but still completes the progressive 200 response under idle-timeout semantics.
     */
    @Test
    public void testRoutedRequestWithoutPolicyIsStreamedAndRetainsIdleTimeout(TestContext ctx) {
        assertProgressiveResponseSucceeds(ctx, HttpMethod.PUT, false, MultiMap.caseInsensitiveMultiMap());
    }

    /**
     * Disables request timeout with x-timeout:0 while retries are configured and asserts a long
     * progressive 200 response can complete successfully in one hit.
     */
    @Test
    public void testDisabledTimeoutAllowsProgressiveResponseWithRetries(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"5xx\":2}");
        assertProgressiveResponseSucceeds(ctx, HttpMethod.GET, true,
                MultiMap.caseInsensitiveMultiMap().set("x-timeout", "0"));
    }

    /**
     * Sends a real routed PUT with {@code Content-Length} under static 5xx retry policy and asserts
     * the body is buffered and replayed for three total 503 hits with the full payload each time.
     */
    @Test
    public void testRoutedPutBodyIsBufferedAndReplayed(TestContext ctx) {
        assertRealRoutedBody(ctx, HttpMethod.PUT, "{\"5xx\":2}", false, 3);
    }

    /**
     * Sends a real routed PUT whose {@code Content-Length} equals the configured
     * {@code maxRetryBodyBytes} and asserts it is still buffered and replayed.
     */
    @Test
    public void testRoutedPutBodyAtBodyLimitIsBufferedAndReplayed(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.MAX_RETRY_BODY_BYTES_PROPERTY, "7");
        assertRealRoutedBody(ctx, HttpMethod.PUT, "{\"5xx\":2}", false, 3);
    }

    /**
     * Sends a real routed PUT whose {@code Content-Length} exceeds the configured
     * {@code maxRetryBodyBytes} and asserts it is streamed without buffering, so it is attempted
     * only once despite the retry policy.
     */
    @Test
    public void testRoutedPutBodyAboveBodyLimitIsStreamedAndNotReplayed(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.MAX_RETRY_BODY_BYTES_PROPERTY, "6");
        assertRealRoutedBody(ctx, HttpMethod.PUT, "{\"5xx\":2}", false, 1);
    }

    /**
     * Sends a real routed chunked PUT (unknown length) under retry policy and asserts it is streamed
     * without buffering, so it is attempted only once.
     */
    @Test
    public void testRoutedChunkedPutBodyIsStreamedAndNotReplayed(TestContext ctx) {
        assertRealRoutedBody(ctx, HttpMethod.PUT, "{\"5xx\":2}", true, 1);
    }

    /**
     * Sends a real routed PUT without retry policy and asserts it stays streamed and is attempted
     * only once, returning the first 503.
     */
    @Test
    public void testRoutedPutBodyWithoutPolicyIsStreamedAndNotReplayed(TestContext ctx) {
        assertRealRoutedBody(ctx, HttpMethod.PUT, null, false, 1);
    }

    /**
     * Sends a real routed POST with retry policy and asserts non-idempotence prevents replay, so the
     * backend sees one full payload and returns the first 503.
     */
    @Test
    public void testRoutedPostBodyIsStreamedButNeverReplayed(TestContext ctx) {
        assertRealRoutedBody(ctx, HttpMethod.POST, "{\"5xx\":2}", false, 1);
    }

    /**
     * Sends the 7-byte body {@code payload} in two delayed parts through a real router that passes
     * no pre-buffered body to the forwarder.
     *
     * @param ctx Vert.x unit context used for async assertions
     * @param method HTTP method to send through the real router
     * @param retriesByStatus static retry policy, or {@code null} for the default (no retries)
     * @param chunked {@code true} to send with {@code Transfer-Encoding: chunked}, {@code false} to
     *                send with {@code Content-Length: 7}
     * @param expectedHits expected backend contacts including the initial attempt
     */
    private void assertRealRoutedBody(TestContext ctx, HttpMethod method, String retriesByStatus,
                                      boolean chunked, int expectedHits) {
        AtomicInteger hits = new AtomicInteger();
        java.util.List<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        Async ready = ctx.async();
        int[] port = new int[1];
        backend = vertx.createHttpServer().requestHandler(req -> {
            hits.incrementAndGet();
            req.bodyHandler(body -> {
                received.add(body.toString());
                req.response().setStatusCode(503).end("attempt-" + received.size());
            });
        });
        backend.listen(0, ctx.asyncAssertSuccess(server -> {
            port[0] = server.actualPort();
            ready.complete();
        }));
        ready.awaitSuccess(AWAIT_MS);
        if (retriesByStatus != null) {
            System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, retriesByStatus);
        }
        Rule rule = buildRule(port[0]);
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
        int proxyPort = startForwardingServer(ctx, forwarder, false);
        Async done = ctx.async();
        vertx.createHttpClient().request(method, proxyPort, "localhost", REQUEST_URI)
                .onComplete(ctx.asyncAssertSuccess(req -> {
                    if (chunked) {
                        req.setChunked(true);
                    } else {
                        req.putHeader("Content-Length", "7");
                    }
                    req.response().onComplete(ctx.asyncAssertSuccess(response -> {
                        ctx.assertEquals(503, response.statusCode());
                        response.body().onComplete(ctx.asyncAssertSuccess(body -> {
                            ctx.assertEquals("attempt-" + expectedHits, body.toString());
                            done.complete();
                        }));
                    }));
                    req.write("pay");
                    vertx.setTimer(100, ignored -> req.end("load"));
                }));
        done.awaitSuccess(AWAIT_MS);
        ctx.assertEquals(expectedHits, hits.get());
        for (String body : received) {
            ctx.assertEquals("payload", body, "Every attempt must carry the complete original body");
        }
    }

    /**
     * Sends a 503 that is translated to 200 by header and asserts retry matching uses the original
     * status first: three backend hits, final translated 200 and third-attempt body.
     */
    @Test
    public void testRetryMatchesOriginalStatusBeforeHeaderTranslation(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-503", "2")
                        .set("x-translate-status-503", "200"));
        ctx.assertEquals(3, hits.get(), "Translation must not suppress retries of the original 503");
        ctx.assertEquals(200, response.status.get());
        ctx.assertEquals("response-3", response.body.toString());
    }

    /**
     * Configures rule-level 503-to-200 translation with static 503 retries and asserts retries are
     * decided on the original 503, yielding three hits and final translated 200.
     */
    @Test
    public void testRetryMatchesOriginalStatusBeforeRuleTranslation(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"503\":2}");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503);
        Rule rule = buildRule(port);
        rule.addTranslateStatus(ImmutableMap.of(java.util.regex.Pattern.compile("503"), 200));
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
        Async done = ctx.async();
        CapturingResponse response = new CapturingResponse(done);
        forwarder.handle(buildRoutingContext(HttpMethod.GET, response), Buffer.buffer("payload"), null);
        done.awaitSuccess(AWAIT_MS);
        ctx.assertEquals(3, hits.get());
        ctx.assertEquals(200, response.status.get());
        ctx.assertEquals("response-3", response.body.toString());
    }

    /**
     * Translates an original 404 into 503 while only 503 retries are configured by header and
     * asserts translation into a retryable status does not create retries.
     */
    @Test
    public void testTranslationIntoRetryableStatusDoesNotEnableRetries(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 404);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-503", "2")
                        .set("x-translate-status-404", "503"));
        ctx.assertEquals(1, hits.get());
        ctx.assertEquals(503, response.status.get());
    }

    /**
     * Starts a progressive 200 backend and forwards through a real server to assert the full body
     * survives the rule idle timeout with exactly one backend hit.
     *
     * @param ctx Vert.x unit context used for async assertions
     * @param method HTTP method to send
     * @param buffered whether the proxy pre-buffers the body before forwarding
     * @param headers request headers to send to the proxy
     */
    private void assertProgressiveResponseSucceeds(TestContext ctx, HttpMethod method, boolean buffered, MultiMap headers) {
        AtomicInteger hits = new AtomicInteger();
        int port = startProgressiveBackend(ctx, hits, 200, null);
        Rule rule = buildRule(port);
        rule.setKeepAlive(true);
        rule.setTimeout(600);
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
        int proxyPort = startForwardingServer(ctx, forwarder, buffered);
        Async done = ctx.async();
        vertx.createHttpClient().request(method, proxyPort, "localhost", REQUEST_URI)
                .compose(req -> {
                    req.headers().addAll(headers);
                    return req.send(Buffer.buffer("payload"));
                }).onComplete(ctx.asyncAssertSuccess(response -> {
                    ctx.assertEquals(200, response.statusCode());
                    response.body().onComplete(ctx.asyncAssertSuccess(body -> {
                        ctx.assertEquals("xxxxxxxxxxxxxxxxxx", body.toString(),
                                "All 18 chunks over 1800ms must survive a 600ms idle timeout");
                        done.complete();
                    }));
                }));
        done.awaitSuccess(AWAIT_MS);
        ctx.assertEquals(1, hits.get());
    }

    /**
     * Starts a real HTTP server that forwards every request through the given forwarder.
     *
     * @param ctx Vert.x unit context used to await the listen result
     * @param forwarder forwarder under test
     * @param buffered whether to buffer the request body before invoking the forwarder
     * @return actual proxy listen port
     */
    private int startForwardingServer(TestContext ctx, Forwarder forwarder, boolean buffered) {
        return startForwardingServer(ctx, forwarder, buffered, null);
    }

    /**
     * Starts a real HTTP server that forwards every request through the given forwarder, passing
     * the given after handler.
     *
     * @param ctx Vert.x unit context used to await the listen result
     * @param forwarder forwarder under test
     * @param buffered whether to buffer the request body before invoking the forwarder
     * @param afterHandler handler passed to the forwarder, or {@code null}
     * @return actual proxy listen port
     */
    private int startForwardingServer(TestContext ctx, Forwarder forwarder, boolean buffered,
                                      Handler<Void> afterHandler) {
        Router router = Router.router(vertx);
        router.route().handler(routingContext -> {
            if (buffered) {
                routingContext.request().bodyHandler(body -> forwarder.handle(routingContext, body, afterHandler));
            } else {
                routingContext.request().pause();
                forwarder.handle(routingContext, null, afterHandler);
            }
        });
        Async ready = ctx.async();
        int[] port = new int[1];
        vertx.createHttpServer().requestHandler(router).listen(0, ctx.asyncAssertSuccess(server -> {
            port[0] = server.actualPort();
            ready.complete();
        }));
        ready.awaitSuccess(AWAIT_MS);
        return port[0];
    }

    /**
     * Starts a backend that reads payload, sends the given status, then writes 18 chunks over time
     * and optionally signals when the client connection closes.
     *
     * @param ctx Vert.x unit context used for assertions and startup
     * @param hits counter incremented for each backend request
     * @param status response status to send
     * @param closed async to complete on connection close, or {@code null}
     * @return actual backend listen port
     */
    private int startProgressiveBackend(TestContext ctx, AtomicInteger hits, int status, Async closed) {
        Async ready = ctx.async();
        int[] port = new int[1];
        backend = vertx.createHttpServer().requestHandler(req -> {
            hits.incrementAndGet();
            req.bodyHandler(body -> {
                ctx.assertEquals("payload", body.toString());
                req.response().setStatusCode(status).setChunked(true);
                AtomicInteger chunks = new AtomicInteger();
                long timer = vertx.setPeriodic(100, id -> {
                    if (chunks.incrementAndGet() == 18) {
                        vertx.cancelTimer(id);
                        req.response().end("x");
                    } else {
                        req.response().write("x");
                    }
                });
                req.connection().closeHandler(ignored -> {
                    vertx.cancelTimer(timer);
                    if (closed != null && !closed.isCompleted()) {
                        closed.complete();
                    }
                });
            });
        });
        backend.listen(0, ctx.asyncAssertSuccess(server -> {
            port[0] = server.actualPort();
            ready.complete();
        }));
        ready.awaitSuccess(AWAIT_MS);
        return port[0];
    }

    /**
     * Configures upstreamFailure:2 and sends an invalid x-route-retry-5xx value; asserts invalid
     * header fallback keeps the static budget, producing three no-response hits and final 502.
     */
    @Test
    public void testInvalidHeader_retainsConfiguredUpstreamFailureBudget(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"upstreamFailure\":2}");
        AtomicInteger hits = new AtomicInteger();
        int port = startCountingBackend(ctx, hits, Integer.MAX_VALUE);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", "-1"));
        ctx.assertEquals(3, hits.get());
        ctx.assertEquals(502, response.status.get());
    }

    /**
     * Starts a status backend without tracking connection reuse.
     *
     * @param ctx Vert.x unit context used for assertions and startup
     * @param hits counter incremented for each backend request
     * @param statuses statuses returned by successive attempts, repeating the last one
     * @return actual backend listen port
     */
    private int startStatusBackend(TestContext ctx, AtomicInteger hits, int... statuses) {
        return startStatusBackend(ctx, hits, new AtomicInteger(), statuses);
    }

    /**
     * Starts a backend that returns configured statuses and records both request hits and connection
     * creations, echoing the attempt number in header and body.
     *
     * @param ctx Vert.x unit context used for assertions and startup
     * @param hits counter incremented for each backend request
     * @param connections counter incremented for each TCP connection
     * @param statuses statuses returned by successive attempts, repeating the last one
     * @return actual backend listen port
     */
    private int startStatusBackend(TestContext ctx, AtomicInteger hits, AtomicInteger connections, int... statuses) {
        Async ready = ctx.async();
        int[] port = new int[1];
        backend = vertx.createHttpServer().connectionHandler(connection -> connections.incrementAndGet()).requestHandler(req -> {
            int attempt = hits.incrementAndGet();
            req.bodyHandler(body -> {
                if (body.length() > 0) {
                    ctx.assertEquals("payload", body.toString());
                }
                req.response().setStatusCode(statuses[Math.min(attempt - 1, statuses.length - 1)])
                        .putHeader("x-backend-attempt", Integer.toString(attempt)).end("response-" + attempt);
            });
        });
        backend.listen(0, ctx.asyncAssertSuccess(server -> {
            port[0] = server.actualPort();
            ready.complete();
        }));
        ready.awaitSuccess(AWAIT_MS);
        return port[0];
    }

    /**
     * Starts a backend whose first attempt waits 750ms then either closes the connection or returns
     * 503, while later attempts remain silent until the caller's timeout expires.
     *
     * @param ctx Vert.x unit context used for assertions and startup
     * @param hitCounter counter incremented for each backend request
     * @param httpError {@code true} to return 503 on the first attempt, {@code false} to close it
     * @return actual backend listen port
     */
    private int startDelayedFailureBackend(TestContext ctx, AtomicInteger hitCounter, boolean httpError) {
        Async ready = ctx.async();
        int[] port = new int[1];
        backend = vertx.createHttpServer();
        backend.requestHandler(req -> {
            int attemptNr = hitCounter.incrementAndGet();
            req.bodyHandler(body -> {
                ctx.assertEquals("payload", body.toString());
                if (attemptNr == 1) {
                    vertx.setTimer(750, ignored -> {
                        if (httpError) {
                            req.response().setStatusCode(503).end("retry");
                        } else {
                            req.connection().close();
                        }
                    });
                }
                // Subsequent attempts stay silent until the remaining timeout expires.
            });
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
     * @param ctx Vert.x unit context used for startup
     * @param hitCounter counter incremented for each backend request
     * @param failTimes number of initial attempts that close without a response
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

    /**
     * Convenience for the common case: forwards an idempotent buffered GET and returns the captured
     * status.
     *
     * @param ctx Vert.x unit context used to await completion
     * @param backendPort backend port to target
     * @return captured downstream status code
     */
    private int forwardBufferedGetAndAwaitStatus(TestContext ctx, int backendPort) {
        return forwardAndAwaitStatus(ctx, backendPort, HttpMethod.GET, Buffer.buffer("payload"));
    }

    /**
     * Forwards a single request through a freshly built {@link Forwarder} (which snapshots the retry
     * property in its constructor) and blocks until the downstream response is ended.
     *
     * @param ctx Vert.x unit context used to await completion
     * @param backendPort backend port to target
     * @param method   the request method to use.
     * @param bodyData the buffered request body, or {@code null} to simulate a streamed body.
     * @return the status code captured on the downstream response.
     */
    private int forwardAndAwaitStatus(TestContext ctx, int backendPort, HttpMethod method, Buffer bodyData) {
        return forwardAndAwaitResponse(ctx, backendPort, method, bodyData, new HeadersMultiMap()).status.get();
    }

    /**
     * Forwards a request with extra headers through a new forwarder without keep-alive.
     *
     * @param ctx Vert.x unit context used to await completion
     * @param backendPort backend port to target
     * @param method request method to use
     * @param bodyData buffered request body, or {@code null} for routed streaming
     * @param headers request headers to expose to the forwarder
     * @return response recorder completed by the forwarder
     */
    private CapturingResponse forwardAndAwaitResponse(TestContext ctx, int backendPort, HttpMethod method,
                                                      Buffer bodyData, MultiMap headers) {
        return forwardAndAwaitResponse(ctx, backendPort, method, bodyData, headers, false);
    }

    /**
     * Forwards a request through the given forwarder and waits until the response ends.
     *
     * @param ctx       Vert.x unit context used to await completion
     * @param forwarder forwarder under test
     * @param method    request method to use
     * @param bodyData  buffered request body, or {@code null} for routed streaming
     * @param headers   request headers to expose to the forwarder
     * @return response recorder completed by the forwarder
     */
    private CapturingResponse forwardAndAwaitResponse(TestContext ctx, Forwarder forwarder, HttpMethod method,
                                                      Buffer bodyData, MultiMap headers) {
        Async done = ctx.async();
        CapturingResponse response = new CapturingResponse(done);
        forwarder.handle(buildRoutingContext(method, response, headers), bodyData, null);
        done.awaitSuccess(AWAIT_MS);
        return response;
    }

    /**
     * Forwards a request through a freshly built forwarder and waits until the response ends.
     *
     * @param ctx Vert.x unit context used to await completion
     * @param backendPort backend port to target
     * @param method request method to use
     * @param bodyData buffered request body, or {@code null} for routed streaming
     * @param headers request headers to expose to the forwarder
     * @param keepAlive whether the rule/client should keep backend connections alive
     * @return response recorder completed by the forwarder
     */
    private CapturingResponse forwardAndAwaitResponse(TestContext ctx, int backendPort, HttpMethod method,
                                                      Buffer bodyData, MultiMap headers, boolean keepAlive) {
        Rule rule = buildRule(backendPort);
        rule.setKeepAlive(keepAlive);
        HttpClient httpClient = vertx.createHttpClient(rule.buildHttpClientOptions());
        Forwarder forwarder = buildForwarder(rule, httpClient);

        Async done = ctx.async();
        CapturingResponse response = new CapturingResponse(done);
        RoutingContext routingContext = buildRoutingContext(method, response, headers);

        forwarder.handle(routingContext, bodyData, null);

        done.awaitSuccess(AWAIT_MS);
        return response;
    }

    /**
     * Builds a forwarder with default mocked logging appenders and no monitoring.
     *
     * @param rule routing rule to use
     * @param httpClient client used for backend calls
     * @return configured forwarder
     */
    private Forwarder buildForwarder(Rule rule, HttpClient httpClient) {
        MockResourceStorage storage = new MockResourceStorage(ImmutableMap.of(RULES_PATH, "{}"));
        return buildForwarder(rule, httpClient, new LoggingResourceManager(vertx, storage, LOGGING_URI),
                mock(LogAppenderRepository.class), null);
    }

    /**
     * Builds a forwarder with explicit logging and monitoring collaborators.
     *
     * @param rule routing rule to use
     * @param httpClient client used for backend calls
     * @param loggingManager logging resource manager to inject
     * @param appenders appender repository to inject
     * @param monitoring monitoring handler to inject, or {@code null}
     * @return configured forwarder
     */
    private Forwarder buildForwarder(Rule rule, HttpClient httpClient, LoggingResourceManager loggingManager,
                                     LogAppenderRepository appenders, MonitoringHandler monitoring) {
        MockResourceStorage storage = new MockResourceStorage(ImmutableMap.of(RULES_PATH, "{}"));
        return new Forwarder(
                vertx,
                httpClient,
                rule,
                storage,
                loggingManager,
                appenders,
                monitoring,
                USER_PROFILE_PATH,
                null
        );
    }

    /**
     * Asserts the values of the three outcome counters of the given metric name and that they
     * carry exactly the bounded tags {@code metricName}, {@code rule} and {@code target}.
     *
     * @param ctx              Vert.x unit context used for assertions
     * @param registry         registry the forwarder publishes to
     * @param metricName       value of the {@code metricName} tag
     * @param expectedRequests expected {@code gateleen_forwarded_requests} count
     * @param expectedRetries  expected {@code gateleen_forwarded_retries} count
     * @param expectedFailures expected {@code gateleen_forwarded_failures} count
     */
    private void assertForwardedCounterValues(TestContext ctx, SimpleMeterRegistry registry, String metricName,
                                              double expectedRequests, double expectedRetries, double expectedFailures) {
        Counter requests = forwardedCounter(registry, Forwarder.FORWARDED_REQUESTS_METRIC_NAME, metricName);
        Counter retries = forwardedCounter(registry, Forwarder.FORWARDED_RETRIES_METRIC_NAME, metricName);
        Counter failures = forwardedCounter(registry, Forwarder.FORWARDED_FAILURES_METRIC_NAME, metricName);
        ctx.assertEquals(expectedRequests, requests.count());
        ctx.assertEquals(expectedRetries, retries.count());
        ctx.assertEquals(expectedFailures, failures.count());
        for (Counter counter : List.of(requests, retries, failures)) {
            // Bounded tags only: metric name, rule pattern and configured target, never request URLs.
            ctx.assertEquals(3, counter.getId().getTags().size());
            ctx.assertNotNull(counter.getId().getTag(Forwarder.FORWARDER_METRIC_TAG_RULE));
            ctx.assertNotNull(counter.getId().getTag(Forwarder.FORWARDER_METRIC_TAG_TARGET));
        }
    }

    /**
     * Looks up an outcome counter by name and {@code metricName} tag.
     *
     * @param registry   registry the forwarder publishes to
     * @param name       counter name
     * @param metricName value of the {@code metricName} tag
     * @return the counter; fails if it is not registered
     */
    private Counter forwardedCounter(SimpleMeterRegistry registry, String name, String metricName) {
        return registry.get(name).tag(AbstractForwarder.FORWARDER_METRIC_TAG_METRICNAME, metricName).counter();
    }

    /**
     * Builds a mocked routing context with default headers and request URI.
     *
     * @param method HTTP method exposed by the fake request
     * @param response response returned by the fake request
     * @return mocked routing context
     */
    private RoutingContext buildRoutingContext(HttpMethod method, HttpServerResponse response) {
        return buildRoutingContext(method, response, new HeadersMultiMap());
    }

    /**
     * Builds a mocked routing context with explicit headers and the default request URI.
     *
     * @param method HTTP method exposed by the fake request
     * @param response response returned by the fake request
     * @param headers headers exposed by the fake request
     * @return mocked routing context
     */
    private RoutingContext buildRoutingContext(HttpMethod method, HttpServerResponse response, MultiMap headers) {
        return buildRoutingContext(method, response, headers, REQUEST_URI);
    }

    /**
     * Builds a mocked routing context whose fake request supplies method, URI, headers and response
     * without a real network client.
     *
     * @param method HTTP method exposed by the fake request
     * @param response response returned by the fake request
     * @param headers headers exposed by the fake request
     * @param uri request URI and path exposed by the fake request
     * @return mocked routing context
     */
    private RoutingContext buildRoutingContext(HttpMethod method, HttpServerResponse response, MultiMap headers, String uri) {
        DummyHttpServerRequest fakeRequest = new DummyHttpServerRequest() {
            @Override public HttpMethod method() { return method; }
            @Override public String uri() { return uri; }
            @Override public String path() { return uri; }
            @Override public MultiMap headers() { return headers; }
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

    /**
     * Creates a baseline rule targeting localhost on the given backend port.
     *
     * @param port backend port for the rule
     * @return configured rule with retry-test defaults
     */
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
        private final AtomicInteger endCalls = new AtomicInteger();
        private final Buffer body = Buffer.buffer();
        private final Async async;
        private boolean completed = false;
        private volatile long completedAtNanos;

        /**
         * Creates a response recorder that completes the supplied async when first ended.
         *
         * @param async async completion to signal once
         */
        private CapturingResponse(Async async) {
            this.async = async;
        }

        /**
         * Records final status, completion time and end-call count, then completes the async only on
         * the first invocation.
         */
        private void finish() {
            endCalls.incrementAndGet();
            status.set(getStatusCode());
            if (!completed) {
                completedAtNanos = System.nanoTime();
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

    /**
     * Configures static 4xx:3 against a backend returning 400 and asserts four total attempts, final
     * 400 and fourth-attempt body are forwarded.
     */
    @Test
    public void testStaticClassPolicy_retriesHttpErrors(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"4xx\":3}");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 400);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap());
        ctx.assertEquals(4, hits.get());
        ctx.assertEquals(400, response.status.get());
        ctx.assertEquals("response-4", response.body.toString());
    }

    /**
     * Configures only HTTP 5xx/502 retry policies against connection-close failures and asserts
     * received-status policies do not retry no-response failures: one hit and 502.
     */
    @Test
    public void testStaticHttpPolicy_doesNotEnableUpstreamFailureRetries(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"5xx\":3,\"502\":3}");
        AtomicInteger hits = new AtomicInteger();
        int port = startCountingBackend(ctx, hits, Integer.MAX_VALUE);
        ctx.assertEquals(502, forwardBufferedGetAndAwaitStatus(ctx, port));
        ctx.assertEquals(1, hits.get());
    }

    /**
     * Configures static exact 503:0 but sends x-route-retry-5xx:2; asserts the class header
     * overrides static exact policy, allowing three hits and final 200.
     */
    @Test
    public void testHeaderClass_overridesStaticExactPolicy(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"503\":0}");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503, 503, 200);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", "2"));
        ctx.assertEquals(3, hits.get());
        ctx.assertEquals(200, response.status.get());
    }

    /**
     * Configures static 503:2 and sends invalid exact header; asserts invalid header falls back to
     * static policy, yielding three hits and final 503.
     */
    @Test
    public void testInvalidHeader_fallsBackToStaticPolicy(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"503\":2}");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-503", "invalid"));
        ctx.assertEquals(3, hits.get());
        ctx.assertEquals(503, response.status.get());
    }

    /**
     * Configures static 5xx:3 but sends exact x-route-retry-503:0 and asserts the zero header
     * disables static retries, leaving one 503 hit.
     */
    @Test
    public void testHeaderZero_disablesStaticPolicy(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"5xx\":3}");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-503", "0"));
        ctx.assertEquals(1, hits.get());
        ctx.assertEquals(503, response.status.get());
    }

    /**
     * Configures static 5xx:0 with upstreamFailure:2 against connection-close failures and asserts
     * no-response retries are independent of HTTP status patterns, producing three hits and 502.
     */
    @Test
    public void testUpstreamFailurePolicy_isIndependentOfHttpPatterns(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"5xx\":0,\"upstreamFailure\":2}");
        AtomicInteger hits = new AtomicInteger();
        int port = startCountingBackend(ctx, hits, Integer.MAX_VALUE);
        ctx.assertEquals(502, forwardBufferedGetAndAwaitStatus(ctx, port));
        ctx.assertEquals(3, hits.get());
    }

    /**
     * Constructs a forwarder with static 5xx:2 then changes the property to zero; asserts the
     * constructor snapshot controls behaviour, resulting in three 503 hits.
     */
    @Test
    public void testStaticPolicy_isSnapshottedAtConstruction(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"5xx\":2}");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503);
        Rule rule = buildRule(port);
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"5xx\":0}");
        Async done = ctx.async();
        CapturingResponse response = new CapturingResponse(done);
        forwarder.handle(buildRoutingContext(HttpMethod.GET, response), Buffer.buffer("payload"), null);
        done.awaitSuccess(AWAIT_MS);
        ctx.assertEquals(3, hits.get());
        ctx.assertEquals(503, response.status.get());
    }

    /**
     * Configures static 5xx:3 for a POST returning 503 and asserts non-idempotent requests are not
     * retried by static policy, leaving one hit and final 503.
     */
    @Test
    public void testStaticPolicy_nonIdempotentRequestIsNotRetried(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"5xx\":3}");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.POST, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap());
        ctx.assertEquals(1, hits.get());
        ctx.assertEquals(503, response.status.get());
    }

    /**
     * Retries a JSON PUT until the fourth 200 and verifies payload logging captures the original
     * buffered body exactly once while every attempt receives the same payload.
     */
    @Test
    public void testRetryLogsOriginalJsonPayloadOnce(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        Async ready = ctx.async();
        int[] port = new int[1];
        Buffer payload = Buffer.buffer("{\"message\":\"original\"}");
        backend = vertx.createHttpServer().requestHandler(req -> req.bodyHandler(body -> {
            ctx.assertEquals(payload, body, "Every attempt must replay the same payload");
            int attempt = hits.incrementAndGet();
            req.response().setStatusCode(attempt < 4 ? 503 : 200).end("ok");
        }));
        backend.listen(0, ctx.asyncAssertSuccess(server -> {
            port[0] = server.actualPort();
            ready.complete();
        }));
        ready.awaitSuccess(AWAIT_MS);

        LoggingResource resource = new LoggingResource();
        resource.addPayloadFilter(ImmutableMap.of("url", REQUEST_URI, "destination", "retry-regression"));
        resource.addFilterDestinations(ImmutableMap.of("retry-regression",
                ImmutableMap.of("address", "unused")));
        LoggingResourceManager manager = mock(LoggingResourceManager.class);
        when(manager.getLoggingResource()).thenReturn(resource);
        Async logged = ctx.async();
        AtomicReference<JsonObject> logRecord = new AtomicReference<>();
        AtomicInteger logCalls = new AtomicInteger();
        AbstractAppender appender = new AbstractAppender("retry-regression", null,
                PatternLayout.createDefaultLayout(), false, null) {
            @Override
            public void append(LogEvent event) {
                logCalls.incrementAndGet();
                logRecord.set(new JsonObject(event.getMessage().getFormattedMessage()));
                logged.complete();
            }
        };
        appender.start();
        LogAppenderRepository appenders = mock(LogAppenderRepository.class);
        when(appenders.hasAppender("retry-regression")).thenReturn(true);
        when(appenders.getAppender("retry-regression")).thenReturn(appender);
        org.apache.logging.log4j.core.Logger logger =
                (org.apache.logging.log4j.core.Logger) LogManager.getLogger("LOG_FILTER_" + REQUEST_URI);
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        Configurator.setLevel(logger.getName(), Level.INFO);
        try {
            Rule rule = buildRule(port[0]);
            Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()),
                    manager, appenders, null);
            Async done = ctx.async();
            CapturingResponse response = new CapturingResponse(done);
            RoutingContext routingContext = buildRoutingContext(HttpMethod.PUT, response,
                    MultiMap.caseInsensitiveMultiMap().set("content-type", "application/json")
                            .set("x-route-retry-5xx", "3"));
            LoggingHandler actualHandler = new LoggingHandler(manager, appenders,
                    routingContext.request(), vertx.eventBus());
            ctx.assertTrue(actualHandler.isActive());
            // Delegate to active, real payload logging while observing capture calls. JSON parsers
            // may accept trailing repeated objects, so the emitted record alone is insufficient.
            try (MockedConstruction<LoggingHandler> handlers = mockConstruction(LoggingHandler.class,
                    withSettings().defaultAnswer(delegatesTo(actualHandler)))) {
                forwarder.handle(routingContext, payload, null);
                done.awaitSuccess(AWAIT_MS);
                logged.awaitSuccess(AWAIT_MS);
                ctx.assertEquals(1, handlers.constructed().size());
                verify(handlers.constructed().get(0), times(1)).appendRequestPayload(payload);
                ctx.assertEquals(4, hits.get());
                ctx.assertEquals(200, response.status.get());
                ctx.assertEquals(1, logCalls.get());
                ctx.assertEquals(new JsonObject(payload), logRecord.get().getJsonObject("request").getJsonObject("body"));
            }
        } finally {
            logger.removeAppender(appender);
            logger.setAdditive(previousAdditive);
            Configurator.setLevel(logger.getName(), previousLevel);
            appender.stop();
        }
    }

    /**
     * Returns a truncated retryable 503 response and asserts it terminates as one 502 without retry,
     * downstream body leakage, duplicate monitoring completion or duplicate forward metrics.
     */
    @Test
    public void testTruncatedRetryResponseCompletesMonitoringOnce(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        Async ready = ctx.async();
        int[] port = new int[1];
        backend = vertx.createHttpServer().requestHandler(req -> req.bodyHandler(body -> {
            hits.incrementAndGet();
            req.response().setStatusCode(503).putHeader("content-length", "100")
                    .write("partial").onComplete(ctx.asyncAssertSuccess(ignored -> req.connection().close()));
        }));
        backend.listen(0, ctx.asyncAssertSuccess(server -> {
            port[0] = server.actualPort();
            ready.complete();
        }));
        ready.awaitSuccess(AWAIT_MS);
        MonitoringHandler monitoring = mock(MonitoringHandler.class);
        when(monitoring.startRequestMetricTracking(anyString(), eq(REQUEST_URI))).thenReturn(123L);
        Rule rule = buildRule(port[0]);
        rule.setMetricName("retry-regression");
        MockResourceStorage storage = new MockResourceStorage(ImmutableMap.of(RULES_PATH, "{}"));
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()),
                new LoggingResourceManager(vertx, storage, LOGGING_URI), mock(LogAppenderRepository.class), monitoring);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            forwarder.setMeterRegistry(registry);
            Async done = ctx.async();
            CapturingResponse response = new CapturingResponse(done);
            forwarder.handle(buildRoutingContext(HttpMethod.GET, response,
                    MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", "3")), Buffer.buffer("payload"), null);
            done.awaitSuccess(AWAIT_MS);
            // Complete an event-loop turn after the terminal callback before inspecting finalization.
            Async settled = ctx.async();
            vertx.runOnContext(ignored -> settled.complete());
            settled.awaitSuccess(AWAIT_MS);
            ctx.assertEquals(1, hits.get(), "A truncated discarded response must terminate, not retry");
            ctx.assertEquals(502, response.status.get());
            ctx.assertEquals(1, response.endCalls.get());
            ctx.assertEquals(0, response.body.length(), "The partial backend body must not reach the caller");
            verify(monitoring, times(1)).startRequestMetricTracking("retry-regression", REQUEST_URI);
            verify(monitoring, times(1)).stopRequestMetricTracking("retry-regression", 123L, REQUEST_URI);
            ctx.assertEquals(1L, registry.get(AbstractForwarder.FORWARDS_METRIC_NAME).timer().count());
            assertForwardedCounterValues(ctx, registry, "retry-regression", 1, 0, 1);
        } finally {
            registry.close();
        }
    }

    /**
     * Retries two 503 responses then succeeds with 200 and asserts monitoring stop and forward
     * metrics are completed exactly once for the successful retried request.
     */
    @Test
    public void testSuccessfulRetryCompletesMetricsOnce(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503, 503, 200);
        MonitoringHandler monitoring = mock(MonitoringHandler.class);
        when(monitoring.startRequestMetricTracking(anyString(), eq(REQUEST_URI))).thenReturn(123L);
        Rule rule = buildRule(port);
        rule.setMetricName("retry-regression");
        MockResourceStorage storage = new MockResourceStorage(ImmutableMap.of(RULES_PATH, "{}"));
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()),
                new LoggingResourceManager(vertx, storage, LOGGING_URI), mock(LogAppenderRepository.class), monitoring);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            forwarder.setMeterRegistry(registry);
            Async done = ctx.async();
            CapturingResponse response = new CapturingResponse(done);
            forwarder.handle(buildRoutingContext(HttpMethod.GET, response,
                    MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", "3")), Buffer.buffer("payload"), null);
            done.awaitSuccess(AWAIT_MS);
            ctx.assertEquals(3, hits.get());
            ctx.assertEquals(200, response.status.get());
            ctx.assertEquals(1, response.endCalls.get());
            verify(monitoring, times(1)).startRequestMetricTracking("retry-regression", REQUEST_URI);
            verify(monitoring, times(1)).stopRequestMetricTracking("retry-regression", 123L, REQUEST_URI);
            ctx.assertEquals(1L, registry.get(AbstractForwarder.FORWARDS_METRIC_NAME).timer().count());
            assertForwardedCounterValues(ctx, registry, "retry-regression", 1, 2, 0);
        } finally {
            registry.close();
        }
    }

    /**
     * Exercises one shared forwarder and registry across requests with zero, one and two retries,
     * including recovered and exhausted retries and a non-retried error.
     */
    @Test
    public void testForwardedRequestAndRetryCountersAreCumulative(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 200, 503, 200, 503, 503, 200, 503, 503, 503, 503);
        Rule rule = buildRule(port);
        rule.setMetricName("retry-counters");
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        try {
            forwarder.setMeterRegistry(registry);

            CapturingResponse noRetry = forwardAndAwaitResponse(ctx, forwarder, HttpMethod.GET, Buffer.buffer("payload"),
                    MultiMap.caseInsensitiveMultiMap());
            ctx.assertEquals(200, noRetry.status.get());
            assertForwardedCounterValues(ctx, registry, "retry-counters", 1, 0, 0);

            CapturingResponse oneRetry = forwardAndAwaitResponse(ctx, forwarder, HttpMethod.GET, Buffer.buffer("payload"),
                    MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", "1"));
            ctx.assertEquals(200, oneRetry.status.get());
            assertForwardedCounterValues(ctx, registry, "retry-counters", 2, 1, 0);

            CapturingResponse twoRetries = forwardAndAwaitResponse(ctx, forwarder, HttpMethod.GET, Buffer.buffer("payload"),
                    MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", "2"));
            ctx.assertEquals(200, twoRetries.status.get());
            assertForwardedCounterValues(ctx, registry, "retry-counters", 3, 3, 0);

            CapturingResponse exhausted = forwardAndAwaitResponse(ctx, forwarder, HttpMethod.GET, Buffer.buffer("payload"),
                    MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", "2"));
            ctx.assertEquals(503, exhausted.status.get());
            assertForwardedCounterValues(ctx, registry, "retry-counters", 4, 5, 1);

            CapturingResponse failedWithoutRetry = forwardAndAwaitResponse(ctx, forwarder, HttpMethod.GET,
                    Buffer.buffer("payload"), MultiMap.caseInsensitiveMultiMap());
            ctx.assertEquals(503, failedWithoutRetry.status.get());
            ctx.assertEquals(10, hits.get());
            assertForwardedCounterValues(ctx, registry, "retry-counters", 5, 5, 2);
        } finally {
            registry.close();
        }
    }

    /**
     * An upstream failing without any response exhausts two retries and counts as one request,
     * two retries and one failure, tagged with the rule pattern and configured target.
     */
    @Test
    public void testUpstreamFailureAfterRetriesCountsOneFailedRequest(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"upstreamFailure\":2}");
        AtomicInteger hits = new AtomicInteger();
        int port = startCountingBackend(ctx, hits, Integer.MAX_VALUE);
        Rule rule = buildRule(port);
        rule.setMetricName("upstream-failure");
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            forwarder.setMeterRegistry(registry);
            CapturingResponse response = forwardAndAwaitResponse(ctx, forwarder, HttpMethod.GET,
                    Buffer.buffer("payload"), MultiMap.caseInsensitiveMultiMap());
            ctx.assertEquals(502, response.status.get());
            ctx.assertEquals(3, hits.get());
            assertForwardedCounterValues(ctx, registry, "upstream-failure", 1, 2, 1);
            Counter requests = forwardedCounter(registry, Forwarder.FORWARDED_REQUESTS_METRIC_NAME, "upstream-failure");
            ctx.assertEquals(REQUEST_URI, requests.getId().getTag(Forwarder.FORWARDER_METRIC_TAG_RULE));
            ctx.assertEquals("localhost:" + port, requests.getId().getTag(Forwarder.FORWARDER_METRIC_TAG_TARGET));
        } finally {
            registry.close();
        }
    }

    /** Local (path) rules expose their target path template instead of host:port. */
    @Test
    public void testLocalRuleTargetTagIsTargetPath(TestContext ctx) {
        Rule rule = buildRule(1);
        rule.setScheme("local");
        rule.setUrlPattern("/eagle/audit/log");
        rule.setPath("/eagle/to-houston-direct/audit/log");
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            forwarder.setMeterRegistry(registry);
            Counter failures = registry.get(Forwarder.FORWARDED_FAILURES_METRIC_NAME).counter();
            ctx.assertEquals("/eagle/audit/log", failures.getId().getTag(Forwarder.FORWARDER_METRIC_TAG_RULE));
            ctx.assertEquals("/eagle/to-houston-direct/audit/log",
                    failures.getId().getTag(Forwarder.FORWARDER_METRIC_TAG_TARGET));
            ctx.assertEquals(0d, failures.count());
        } finally {
            registry.close();
        }
    }

    // -------------------------------------------------------------------------
    // Outcome counters: failure classification
    // -------------------------------------------------------------------------

    /**
     * Never hands out a pooled connection and asserts the pool-wait timeout (504 from Gateleen,
     * no backend response) counts as one request and one failure.
     */
    @Test
    public void testPoolWaitTimeoutCountsOneFailure(TestContext ctx) {
        Rule rule = buildRule(1);
        rule.setTimeout(300);
        rule.setMetricName("pool-wait");
        // Connection acquisition never completes.
        Forwarder forwarder = buildForwarder(rule, mock(HttpClient.class));
        assertOutcome(ctx, forwarder, "pool-wait", MultiMap.caseInsensitiveMultiMap(), 504, 1, 0, 1);
    }

    /**
     * Targets a port without listener and asserts a refused connection (503 from Gateleen) counts
     * as one request and one failure.
     */
    @Test
    public void testConnectionRefusedCountsOneFailure(TestContext ctx) {
        Rule rule = buildRule(unusedPort(ctx));
        rule.setMetricName("refused");
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
        assertOutcome(ctx, forwarder, "refused", MultiMap.caseInsensitiveMultiMap(), 503, 1, 0, 1);
    }

    /**
     * Retries a refused connection once ({@code x-route-retry-502: 1}) and asserts one request, one
     * retry and one failure.
     */
    @Test
    public void testRetriedConnectionRefusedCountsRetryAndOneFailure(TestContext ctx) {
        Rule rule = buildRule(unusedPort(ctx));
        rule.setMetricName("refused-retried");
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
        assertOutcome(ctx, forwarder, "refused-retried",
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-502", "1"), 503, 1, 1, 1);
    }

    /**
     * Exhausts one 4xx retry against a backend always answering 400 and asserts client errors are
     * no failures: one request, one retry, no failure.
     */
    @Test
    public void testExhausted4xxRetriesCountNoFailure(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        Rule rule = buildRule(startStatusBackend(ctx, hits, 400));
        rule.setMetricName("client-error");
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
        assertOutcome(ctx, forwarder, "client-error",
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-4xx", "1"), 400, 1, 1, 0);
        ctx.assertEquals(2, hits.get());
    }

    /**
     * Lets the request-wide deadline expire while the retry waits for a response head and asserts
     * the 502 from Gateleen counts as one failure with one retry.
     */
    @Test
    public void testDeadlineExpiryBeforeResponseCountsOneFailure(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        Rule rule = buildRule(startDelayedFailureBackend(ctx, hits, true));
        rule.setMetricName("deadline");
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
        assertOutcome(ctx, forwarder, "deadline", MultiMap.caseInsensitiveMultiMap()
                .set("x-route-retry-5xx", "100").set("x-timeout", "1200"), 502, 1, 1, 1);
        ctx.assertEquals(2, hits.get());
    }

    /**
     * Lets the deadline expire while a 200 is being streamed and asserts no failure is counted:
     * the metrics were completed with the accepted backend status, before the body failed.
     */
    @Test
    public void testDeadlineExpiryMidResponseCountsNoFailure(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        Async closed = ctx.async();
        Rule rule = buildRule(startProgressiveBackend(ctx, hits, 200, closed));
        rule.setTimeout(600);
        rule.setMetricName("mid-stream");
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"5xx\":2}");
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            forwarder.setMeterRegistry(registry);
            int proxyPort = startForwardingServer(ctx, forwarder, true);
            Async done = ctx.async();
            vertx.createHttpClient().request(HttpMethod.GET, proxyPort, "localhost", REQUEST_URI)
                    .compose(req -> req.send(Buffer.buffer("payload")))
                    .onComplete(ctx.asyncAssertSuccess(response -> {
                        ctx.assertEquals(200, response.statusCode());
                        response.body().onComplete(result -> {
                            ctx.assertTrue(result.failed(), "Partial response must be closed");
                            done.complete();
                        });
                    }));
            done.awaitSuccess(AWAIT_MS);
            closed.awaitSuccess(AWAIT_MS);
            assertForwardedCounterValues(ctx, registry, "mid-stream", 1, 0, 0);
        } finally {
            registry.close();
        }
    }

    /**
     * Translates a backend 503 to 200 for the client and asserts the failure is still counted,
     * because the counters reflect the backend status before translation.
     */
    @Test
    public void testTranslatedBackendErrorCountsOneFailure(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        Rule rule = buildRule(startStatusBackend(ctx, hits, 503));
        rule.setMetricName("translated-error");
        rule.addTranslateStatus(ImmutableMap.of(java.util.regex.Pattern.compile("503"), 200));
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
        assertOutcome(ctx, forwarder, "translated-error", MultiMap.caseInsensitiveMultiMap(), 200, 1, 0, 1);
    }

    /**
     * Translates a backend 200 to 500 for the client and asserts no failure is counted, because the
     * counters reflect the backend status before translation.
     */
    @Test
    public void testTranslatedBackendSuccessCountsNoFailure(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        Rule rule = buildRule(startStatusBackend(ctx, hits, 200));
        rule.setMetricName("translated-success");
        rule.addTranslateStatus(ImmutableMap.of(java.util.regex.Pattern.compile("200"), 500));
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
        assertOutcome(ctx, forwarder, "translated-success", MultiMap.caseInsensitiveMultiMap(), 500, 1, 0, 0);
    }

    /**
     * Lets the backend answer 200 with an unsupported transfer coding ({@code gzip, chunked}), which
     * Gateleen rejects with 502, and asserts the rejection counts as one failure although the
     * backend status was 200.
     */
    @Test
    public void testUnsupportedTransferEncodingCountsOneFailure(TestContext ctx) {
        Async ready = ctx.async();
        int[] port = new int[1];
        vertx.createNetServer().connectHandler(socket -> socket.handler(request -> socket.write(Buffer.buffer(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip, chunked\r\nConnection: close\r\n\r\n"
                        + "5\r\nhello\r\n0\r\n\r\n")))).listen(0, ctx.asyncAssertSuccess(server -> {
            port[0] = server.actualPort();
            ready.complete();
        }));
        ready.awaitSuccess(AWAIT_MS);
        Rule rule = buildRule(port[0]);
        rule.setMetricName("bad-framing");
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
        assertOutcome(ctx, forwarder, "bad-framing", MultiMap.caseInsensitiveMultiMap(), 502, 1, 0, 1);
    }

    /**
     * Forwards one buffered GET through the forwarder with a fresh registry and asserts the client
     * status and the outcome counters.
     *
     * @param ctx              Vert.x unit context used for assertions
     * @param forwarder        forwarder under test (without registry yet)
     * @param metricName       the rule's metric name
     * @param headers          request headers
     * @param expectedStatus   expected client status
     * @param expectedRequests expected request count
     * @param expectedRetries  expected retry count
     * @param expectedFailures expected failure count
     */
    private void assertOutcome(TestContext ctx, Forwarder forwarder, String metricName, MultiMap headers,
                               int expectedStatus, double expectedRequests, double expectedRetries,
                               double expectedFailures) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            forwarder.setMeterRegistry(registry);
            CapturingResponse response = forwardAndAwaitResponse(ctx, forwarder, HttpMethod.GET,
                    Buffer.buffer("payload"), headers);
            ctx.assertEquals(expectedStatus, response.status.get());
            assertForwardedCounterValues(ctx, registry, metricName, expectedRequests, expectedRetries, expectedFailures);
        } finally {
            registry.close();
        }
    }

    // -------------------------------------------------------------------------
    // Outcome counters: lifecycle
    // -------------------------------------------------------------------------

    /**
     * Shuts down one of two forwarders with the same tags and asserts the shared counters remain
     * (with their value) until the last forwarder is shut down; repeated shutdown has no effect.
     */
    @Test
    public void testShutdownRemovesOutcomeCountersOfLastUser(TestContext ctx) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            Rule rule = buildRule(1);
            rule.setMetricName("lifecycle");
            Forwarder first = buildForwarder(rule, mock(HttpClient.class));
            Forwarder second = buildForwarder(rule, mock(HttpClient.class));
            first.setMeterRegistry(registry);
            second.setMeterRegistry(registry);
            Counter requests = forwardedCounter(registry, Forwarder.FORWARDED_REQUESTS_METRIC_NAME, "lifecycle");
            requests.increment();

            first.shutdown();
            first.shutdown();
            ctx.assertTrue(requests == registry.find(Forwarder.FORWARDED_REQUESTS_METRIC_NAME)
                    .tag(AbstractForwarder.FORWARDER_METRIC_TAG_METRICNAME, "lifecycle").counter());
            ctx.assertEquals(1d, requests.count());

            second.shutdown();
            for (String name : List.of(Forwarder.FORWARDED_REQUESTS_METRIC_NAME,
                    Forwarder.FORWARDED_RETRIES_METRIC_NAME, Forwarder.FORWARDED_FAILURES_METRIC_NAME)) {
                ctx.assertNull(registry.find(name).counter(), name + " must be removed with its last user");
            }
        } finally {
            registry.close();
        }
    }

    /**
     * Sets the same registry twice and asserts a single shutdown still removes the counters, i.e.
     * re-setting the registry does not leak a user.
     */
    @Test
    public void testSettingRegistryTwiceDoesNotLeakCounterUsers(TestContext ctx) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            Forwarder forwarder = buildForwarder(buildRule(1), mock(HttpClient.class));
            forwarder.setMeterRegistry(registry);
            forwarder.setMeterRegistry(registry);
            ctx.assertNotNull(registry.find(Forwarder.FORWARDED_REQUESTS_METRIC_NAME).counter());
            forwarder.shutdown();
            ctx.assertNull(registry.find(Forwarder.FORWARDED_REQUESTS_METRIC_NAME).counter());
        } finally {
            registry.close();
        }
    }

    /**
     * Sets the registry to {@code null} and asserts the counters are released, and that requests
     * forwarded afterwards are not counted (and do not fail).
     */
    @Test
    public void testNullRegistryReleasesCounters(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            Rule rule = buildRule(startStatusBackend(ctx, hits, 200));
            Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
            forwarder.setMeterRegistry(registry);
            forwarder.setMeterRegistry(null);
            ctx.assertNull(registry.find(Forwarder.FORWARDED_REQUESTS_METRIC_NAME).counter());
            CapturingResponse response = forwardAndAwaitResponse(ctx, forwarder, HttpMethod.GET,
                    Buffer.buffer("payload"), MultiMap.caseInsensitiveMultiMap());
            ctx.assertEquals(200, response.status.get());
            ctx.assertNull(registry.find(Forwarder.FORWARDED_REQUESTS_METRIC_NAME).counter());
        } finally {
            registry.close();
        }
    }

    /**
     * Shuts down a forwarder and asserts requests it still handles afterwards are forwarded but no
     * longer counted, and the removed counters are not re-registered.
     */
    @Test
    public void testRequestsAfterShutdownAreNotCounted(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            Rule rule = buildRule(startStatusBackend(ctx, hits, 200));
            Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
            forwarder.setMeterRegistry(registry);
            forwarder.shutdown();
            CapturingResponse response = forwardAndAwaitResponse(ctx, forwarder, HttpMethod.GET,
                    Buffer.buffer("payload"), MultiMap.caseInsensitiveMultiMap());
            ctx.assertEquals(200, response.status.get());
            ctx.assertEquals(1, hits.get());
            ctx.assertNull(registry.find(Forwarder.FORWARDED_REQUESTS_METRIC_NAME).counter());
        } finally {
            registry.close();
        }
    }

    /** A URL rule with neither host nor host wildcard is tagged {@code unknown:<port>}, not {@code null}. */
    @Test
    public void testMissingHostIsTaggedUnknown(TestContext ctx) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            Rule rule = buildRule(8080);
            rule.setHost(null);
            buildForwarder(rule, mock(HttpClient.class)).setMeterRegistry(registry);
            ctx.assertEquals(Forwarder.UNKNOWN_TAG_VALUE + ":8080", registry.get(Forwarder.FORWARDED_REQUESTS_METRIC_NAME)
                    .counter().getId().getTag(Forwarder.FORWARDER_METRIC_TAG_TARGET));
        } finally {
            registry.close();
        }
    }

    /** A wildcard URL rule is tagged with the configured wildcards, never with a resolved backend. */
    @Test
    public void testWildcardTargetIsTaggedWithWildcards(TestContext ctx) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            Rule rule = buildRule(8080);
            rule.setHost(null);
            rule.setHostWildcard("$1");
            rule.setPortWildcard("$2");
            buildForwarder(rule, mock(HttpClient.class)).setMeterRegistry(registry);
            ctx.assertEquals("$1:$2", registry.get(Forwarder.FORWARDED_REQUESTS_METRIC_NAME)
                    .counter().getId().getTag(Forwarder.FORWARDER_METRIC_TAG_TARGET));
        } finally {
            registry.close();
        }
    }

    /**
     * Local rules only re-enter this Gateleen instance; the rule handling the rewritten path
     * retries. The local hop itself must not retry, otherwise the attempts multiply.
     */
    @Test
    public void testLocalRuleDoesNotRetry(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"upstreamFailure\":2,\"502\":2}");
        AtomicInteger hits = new AtomicInteger();
        int port = startCountingBackend(ctx, hits, Integer.MAX_VALUE);
        Rule rule = buildRule(port);
        rule.setScheme("local");
        rule.setMetricName("local-no-retry");
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().set("x-route-retry-502", "2");
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            forwarder.setMeterRegistry(registry);
            List<String> retryLogs = captureForwarderLogs(() -> {
                CapturingResponse response = forwardAndAwaitResponse(ctx, forwarder, HttpMethod.GET,
                        Buffer.buffer("payload"), headers);
                ctx.assertEquals(502, response.status.get());
                return response;
            }).stream()
                    .filter(e -> e.contains("Retrying request") || e.matches("\\w+ Request \\S+ \\S+ (succeeded|failed) after .*"))
                    .collect(Collectors.toList());
            ctx.assertEquals(1, hits.get());
            ctx.assertTrue(retryLogs.isEmpty(), "A local rule must neither retry nor log a retry summary: " + retryLogs);
            assertForwardedCounterValues(ctx, registry, "local-no-retry", 1, 0, 1);
        } finally {
            registry.close();
        }
    }

    /**
     * Chains a local rule through the self client into a retrying URL rule, as the router does
     * for {@code path} rules, and asserts the retry header is honoured exactly once: three backend
     * attempts for {@code x-route-retry-5xx: 2} (not 3 x 3), and only the inner hop counts retries.
     */
    @Test
    public void testLocalChainRetriesOnlyOnInnerHop(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503);
        Rule innerRule = buildRule(port);
        innerRule.setMetricName("inner");
        Forwarder inner = buildForwarder(innerRule, vertx.createHttpClient(innerRule.buildHttpClientOptions()));
        Rule outerRule = buildRule(port);
        outerRule.setScheme("local");
        outerRule.setMetricName("outer");
        Forwarder outer = buildForwarder(outerRule, newSelfClient(inner));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            inner.setMeterRegistry(registry);
            outer.setMeterRegistry(registry);
            CapturingResponse response = forwardAndAwaitResponse(ctx, outer, HttpMethod.GET, Buffer.buffer("payload"),
                    MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", "2"));
            ctx.assertEquals(503, response.status.get());
            ctx.assertEquals(3, hits.get());
            assertForwardedCounterValues(ctx, registry, "inner", 1, 2, 1);
            assertForwardedCounterValues(ctx, registry, "outer", 1, 0, 1);
        } finally {
            registry.close();
        }
    }

    /**
     * Lets the deadline of a retry-enabled rule expire while it streams a 200 to a Gateleen-internal
     * caller (self client) and asserts the caller sees an aborted response: an exception after the
     * partial body, no end, and no error text appended to the body.
     */
    @Test
    public void testInternalCallerSeesAbortOnDeadlineExpiryMidResponse(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        Async closed = ctx.async();
        Rule rule = buildRule(startProgressiveBackend(ctx, hits, 200, closed));
        rule.setTimeout(600);
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"5xx\":2}");
        Forwarder inner = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
        HttpClient self = newSelfClient(inner);
        Async aborted = ctx.async();
        Buffer received = Buffer.buffer();
        AtomicInteger ends = new AtomicInteger();
        AtomicInteger status = new AtomicInteger();
        self.request(HttpMethod.GET, 0, "localhost", REQUEST_URI, ar -> {
            HttpClientRequest req = ar.result();
            req.response(rar -> {
                HttpClientResponse r = rar.result();
                status.set(r.statusCode());
                r.handler(received::appendBuffer);
                r.endHandler(v -> ends.incrementAndGet());
                r.exceptionHandler(ex -> {
                    ctx.assertTrue(ex instanceof io.vertx.core.http.HttpClosedException, String.valueOf(ex));
                    aborted.complete();
                });
            });
            req.end(Buffer.buffer("payload"));
        });
        aborted.awaitSuccess(AWAIT_MS);
        closed.awaitSuccess(AWAIT_MS);
        Async settled = ctx.async();
        vertx.setTimer(200, id -> settled.complete());
        settled.awaitSuccess(AWAIT_MS);
        ctx.assertEquals(200, status.get());
        ctx.assertEquals(0, ends.get(), "An aborted response must not end normally");
        ctx.assertTrue(received.length() > 0 && received.toString().matches("x+"),
                "Only backend data may reach the caller, got: " + received);
    }

    /**
     * Chains a local rule (served over real HTTP) into a retry-enabled rule whose deadline expires
     * mid-response and asserts the abort propagates through the local hop to the external client,
     * which sees the connection closed instead of a completed response.
     */
    @Test
    public void testLocalChainPropagatesMidResponseAbortToClient(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        Async closed = ctx.async();
        Rule innerRule = buildRule(startProgressiveBackend(ctx, hits, 200, closed));
        innerRule.setTimeout(600);
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"5xx\":2}");
        Forwarder inner = buildForwarder(innerRule, vertx.createHttpClient(innerRule.buildHttpClientOptions()));
        Rule outerRule = buildRule(1);
        outerRule.setScheme("local");
        Forwarder outer = buildForwarder(outerRule, newSelfClient(inner));
        int proxyPort = startForwardingServer(ctx, outer, false);
        Async done = ctx.async();
        vertx.createHttpClient().request(HttpMethod.GET, proxyPort, "localhost", REQUEST_URI)
                .compose(req -> req.send(Buffer.buffer("payload")))
                .onComplete(ctx.asyncAssertSuccess(response -> {
                    ctx.assertEquals(200, response.statusCode());
                    response.body().onComplete(result -> {
                        ctx.assertTrue(result.failed(), "The partial response must be aborted, got: "
                                + (result.succeeded() ? result.result() : null));
                        done.complete();
                    });
                }));
        done.awaitSuccess(AWAIT_MS);
        closed.awaitSuccess(AWAIT_MS);
        ctx.assertEquals(1, hits.get());
    }

    /**
     * Creates a Gateleen self client ({@link org.swisspush.gateleen.core.http.LocalHttpClient})
     * whose requests are handled by the given forwarder, like the router does for local rules.
     *
     * @param target forwarder handling every request of the self client
     * @return the self client
     */
    private HttpClient newSelfClient(Forwarder target) {
        return new org.swisspush.gateleen.core.http.LocalHttpClient(vertx,
                () -> routingContext -> target.handle(routingContext, null, null),
                org.swisspush.gateleen.core.exception.GateleenExceptionFactory.newGateleenThriftyExceptionFactory());
    }

    /**
     * A local rule passes retry headers on to the rule handling the rewritten path, while other
     * rules consume them.
     */
    @Test
    public void testOnlyLocalRulesPassRetryHeadersOn(TestContext ctx) {
        AtomicReference<String> received = new AtomicReference<>();
        Async ready = ctx.async();
        int[] port = new int[1];
        backend = vertx.createHttpServer().requestHandler(req -> {
            received.set(req.getHeader("x-route-retry-502"));
            req.response().end("ok");
        });
        backend.listen(0, ctx.asyncAssertSuccess(server -> {
            port[0] = server.actualPort();
            ready.complete();
        }));
        ready.awaitSuccess(AWAIT_MS);

        for (String scheme : new String[]{"local", "http"}) {
            received.set(null);
            Rule rule = buildRule(port[0]);
            rule.setScheme(scheme);
            Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
            CapturingResponse response = forwardAndAwaitResponse(ctx, forwarder, HttpMethod.GET,
                    Buffer.buffer("payload"), MultiMap.caseInsensitiveMultiMap().set("x-route-retry-502", "1"));
            ctx.assertEquals(200, response.status.get());
            ctx.assertEquals("local".equals(scheme) ? "1" : null, received.get(), scheme);
        }
    }

    /**
     * Confirms a forwarder constructed without a registry still forwards requests normally.
     */
    @Test
    public void testForwardingWithoutMeterRegistry(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 200);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap());
        ctx.assertEquals(200, response.status.get());
        ctx.assertEquals(1, hits.get());
    }

    /**
     * Simulates a buffered-body write failure where the exception callback arrives before the
     * response failure and asserts upstreamFailure retry succeeds with one final 200 completion.
     */
    @Test
    public void testBufferedWriteFailure_exceptionBeforeResponseFailureRetries(TestContext ctx) {
        assertBufferedWriteFailureRetries(ctx, true);
    }

    /**
     * Simulates a buffered-body write failure where the response failure arrives before the
     * exception callback and asserts the stale exception does not prevent the retry from succeeding.
     */
    @Test
    public void testBufferedWriteFailure_responseFailureBeforeExceptionRetries(TestContext ctx) {
        assertBufferedWriteFailureRetries(ctx, false);
    }

    /**
     * Disables upstreamFailure retries during a buffered write failure and asserts the failed first
     * attempt completes once with 502 and no backend retry.
     */
    @Test
    public void testBufferedWriteFailure_withoutRetryCompletesOnce(TestContext ctx) {
        assertBufferedWriteFailure(ctx, true, false);
    }

    /**
     * Like {@link #testBufferedWriteFailure_withoutRetryCompletesOnce} but with the response failure
     * arriving before the request exception; asserts the same single completion and that the late
     * request exception is still logged once (WARN plus ERROR).
     */
    @Test
    public void testBufferedWriteFailure_withoutRetryResponseFailureFirstLogsOnce(TestContext ctx) {
        assertBufferedWriteFailure(ctx, false, false);
    }

    /**
     * Runs the buffered write-failure scenario with retries enabled.
     *
     * @param ctx Vert.x unit context used for async assertions
     * @param exceptionFirst whether to invoke the request exception callback before response failure
     */
    private void assertBufferedWriteFailureRetries(TestContext ctx, boolean exceptionFirst) {
        assertBufferedWriteFailure(ctx, exceptionFirst, true);
    }

    /**
     * Simulates both callback orderings for a buffered-body send failure and asserts retry-enabled
     * requests replay successfully while retry-disabled requests finish once with 502 and metrics
     * stop once. The request exception is logged (WARN plus ERROR) only if no retry follows.
     *
     * @param ctx Vert.x unit context used for async assertions
     * @param exceptionFirst whether to invoke the request exception callback before response failure
     * @param retryEnabled whether upstreamFailure retry budget is enabled
     */
    private void assertBufferedWriteFailure(TestContext ctx, boolean exceptionFirst, boolean retryEnabled) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY,
                retryEnabled ? "{\"upstreamFailure\":1}" : "{\"upstreamFailure\":0}");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 200);
        Rule rule = buildRule(port);
        rule.setMetricName("write-failure");
        HttpClient actualClient = vertx.createHttpClient(rule.buildHttpClientOptions());
        HttpClient client = mock(HttpClient.class);
        HttpClientRequest failedRequest = mock(HttpClientRequest.class, RETURNS_SELF);
        when(failedRequest.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        AtomicReference<Handler<Throwable>> exceptionHandler = new AtomicReference<>();
        AtomicReference<Handler<AsyncResult<HttpClientResponse>>> responseHandler = new AtomicReference<>();
        doAnswer(invocation -> {
            exceptionHandler.set(invocation.getArgument(0));
            return failedRequest;
        }).when(failedRequest).exceptionHandler(any());
        doAnswer(invocation -> {
            responseHandler.set(invocation.getArgument(0));
            return failedRequest;
        }).when(failedRequest).response(any());
        AtomicInteger acquisitions = new AtomicInteger();
        doAnswer(invocation -> {
            Handler<AsyncResult<HttpClientRequest>> acquired = invocation.getArgument(4);
            if (acquisitions.incrementAndGet() == 1) {
                vertx.runOnContext(ignored -> acquired.handle(Future.succeededFuture(failedRequest)));
            } else {
                actualClient.request(HttpMethod.GET, port, "localhost", REQUEST_URI, acquired);
            }
            return client;
        }).when(client).request(any(HttpMethod.class), anyInt(), anyString(), anyString(), any());
        doAnswer(invocation -> {
            ctx.assertEquals(Buffer.buffer("payload"), invocation.getArgument(0));
            IllegalStateException failure = new IllegalStateException("Connection failed while writing buffered body");
            // Both callbacks belong to the same failed attempt. Exercise both legal orderings,
            // including a stale exception arriving after the response callback has started a retry.
            vertx.runOnContext(ignored -> {
                if (exceptionFirst) {
                    exceptionHandler.get().handle(failure);
                    responseHandler.get().handle(Future.failedFuture(failure));
                } else {
                    responseHandler.get().handle(Future.failedFuture(failure));
                    exceptionHandler.get().handle(failure);
                }
            });
            return Future.failedFuture(failure);
        }).when(failedRequest).send(any(Buffer.class));
        MonitoringHandler monitoring = mock(MonitoringHandler.class);
        when(monitoring.startRequestMetricTracking(anyString(), eq(REQUEST_URI))).thenReturn(123L);
        MockResourceStorage storage = new MockResourceStorage(ImmutableMap.of(RULES_PATH, "{}"));
        Forwarder forwarder = buildForwarder(rule, client,
                new LoggingResourceManager(vertx, storage, LOGGING_URI), mock(LogAppenderRepository.class), monitoring);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            forwarder.setMeterRegistry(registry);
            Async done = ctx.async();
            CapturingResponse response = new CapturingResponse(done);
            List<String> logs = captureForwarderLogs(() -> {
                forwarder.handle(buildRoutingContext(HttpMethod.GET, response), Buffer.buffer("payload"), null);
                done.awaitSuccess(AWAIT_MS);
                // A request exception arriving after the response failure is logged after the response.
                Async settled = ctx.async();
                vertx.setTimer(200, ignored -> settled.complete());
                settled.awaitSuccess(AWAIT_MS);
                return null;
            });
            ctx.assertEquals(retryEnabled ? 2 : 1, acquisitions.get());
            ctx.assertEquals(retryEnabled ? 1 : 0, hits.get());
            ctx.assertEquals(retryEnabled ? 200 : 502, response.status.get());
            if (retryEnabled) {
                ctx.assertEquals("response-1", response.body.toString(),
                        "The real retry must replay the buffered payload successfully");
            }
            ctx.assertEquals(1, response.endCalls.get());
            verify(failedRequest, times(1)).exceptionHandler(any());
            verify(monitoring, times(1)).stopRequestMetricTracking("write-failure", 123L, REQUEST_URI);
            ctx.assertEquals(1L, registry.get(AbstractForwarder.FORWARDS_METRIC_NAME).timer().count());
            assertForwardedCounterValues(ctx, registry, "write-failure", 1, retryEnabled ? 1 : 0, retryEnabled ? 0 : 1);
            // The request exception of the final failed attempt is logged as before retries
            // existed (WARN plus ERROR, whatever the callback order); a superseded attempt is not.
            long warnings = logs.stream().filter(e -> e.equals("WARN Failed to 'GET " + REQUEST_URI + "'")).count();
            long errors = logs.stream().filter(e -> e.equals("ERROR http://localhost:" + port + REQUEST_URI
                    + " Connection failed while writing buffered body")).count();
            ctx.assertEquals(retryEnabled ? 0L : 1L, warnings, "Request failure WARN: " + logs);
            ctx.assertEquals(retryEnabled ? 0L : 1L, errors, "Request failure ERROR: " + logs);
        } finally {
            registry.close();
        }
    }

    /**
     * Makes a header function fail before any backend request while retries and a short timeout are
     * configured; asserts a single 400 response, no hits, no deadline close and one metric stop.
     */
    @Test
    public void testHeaderFunctionErrorCancelsDeadlineAndCompletesMetrics(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"upstreamFailure\":1}");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 200);
        Rule rule = buildRule(port);
        rule.setTimeout(300);
        rule.setMetricName("header-error");
        HeaderFunctions.EvalScope scope = mock(HeaderFunctions.EvalScope.class);
        when(scope.getErrorMessage()).thenReturn("invalid header function");
        rule.setHeaderFunction(headers -> scope);
        MonitoringHandler monitoring = mock(MonitoringHandler.class);
        when(monitoring.startRequestMetricTracking(anyString(), eq(REQUEST_URI))).thenReturn(123L);
        MockResourceStorage storage = new MockResourceStorage(ImmutableMap.of(RULES_PATH, "{}"));
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()),
                new LoggingResourceManager(vertx, storage, LOGGING_URI), mock(LogAppenderRepository.class), monitoring);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger closes = new AtomicInteger();
        try {
            forwarder.setMeterRegistry(registry);
            Async done = ctx.async();
            CapturingResponse response = new CapturingResponse(done) {
                @Override public boolean headWritten() { return getStatusCode() == 400; }
                @Override public void close() { closes.incrementAndGet(); }
            };
            forwarder.handle(buildRoutingContext(HttpMethod.GET, response), Buffer.buffer("payload"), null);
            done.awaitSuccess(AWAIT_MS);
            ctx.assertEquals(400, response.status.get());
            verify(monitoring, times(1)).stopRequestMetricTracking("header-error", 123L, REQUEST_URI);
            ctx.assertEquals(1L, registry.get(AbstractForwarder.FORWARDS_METRIC_NAME).timer().count());
            assertForwardedCounterValues(ctx, registry, "header-error", 1, 0, 0);
            Async pastDeadline = ctx.async();
            vertx.setTimer(600, ignored -> pastDeadline.complete());
            pastDeadline.awaitSuccess(AWAIT_MS);
            ctx.assertEquals(0, closes.get(), "Completed 400 response must not be closed by an expired deadline");
            ctx.assertEquals(1, response.endCalls.get());
            ctx.assertEquals(400, response.getStatusCode());
            ctx.assertEquals(0, hits.get(), "Header errors must not send or retry the upstream request");
            verify(monitoring, times(1)).stopRequestMetricTracking("header-error", 123L, REQUEST_URI);
            ctx.assertEquals(1L, registry.get(AbstractForwarder.FORWARDS_METRIC_NAME).timer().count());
            assertForwardedCounterValues(ctx, registry, "header-error", 1, 0, 0);
        } finally {
            registry.close();
        }
    }

    /**
     * Sends an enormous header retry count with x-timeout disabled and asserts the default ceiling
     * caps it to six total 503 hits and forwards the sixth response body.
     */
    @Test
    public void testDefaultCeilingBoundsHeaderRetriesWithoutTimeout(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", "2147483647").set("x-timeout", "0"));
        ctx.assertEquals(6, hits.get());
        ctx.assertEquals(503, response.status.get());
        ctx.assertEquals("response-6", response.body.toString());
    }

    /**
     * Sets maxRetries=2 with static 5xx:100 and asserts the custom ceiling caps retries to three
     * total attempts ending in 503.
     */
    @Test
    public void testCustomCeilingBoundsStaticRetries(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.MAX_RETRIES_PROPERTY, "2");
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"5xx\":100}");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503);
        ctx.assertEquals(503, forwardBufferedGetAndAwaitStatus(ctx, port));
        ctx.assertEquals(3, hits.get());
    }

    /**
     * Configures upstreamFailure:99 and asserts the default ceiling caps no-response retries to six
     * total hits before returning 502.
     */
    @Test
    public void testDefaultCeilingBoundsUpstreamFailureRetries(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"upstreamFailure\":99}");
        AtomicInteger hits = new AtomicInteger();
        int port = startCountingBackend(ctx, hits, Integer.MAX_VALUE);
        ctx.assertEquals(502, forwardBufferedGetAndAwaitStatus(ctx, port));
        ctx.assertEquals(6, hits.get());
    }

    /**
     * Sets maxRetries=0 with x-route-retry-5xx:3 and asserts the ceiling prevents header retries,
     * leaving one 503 hit.
     */
    @Test
    public void testZeroCeilingPreventsHeaderRetries(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.MAX_RETRIES_PROPERTY, "0");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", "3"));
        ctx.assertEquals(1, hits.get());
        ctx.assertEquals(503, response.status.get());
    }

    /**
     * Configures only upstreamFailure:3 against a received 502 response and asserts received 502 is
     * not treated as a no-response failure, so only one hit occurs.
     */
    @Test
    public void testUpstreamFailureSettingDoesNotRetryReceived502(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"upstreamFailure\":3}");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 502);
        ctx.assertEquals(502, forwardBufferedGetAndAwaitStatus(ctx, port));
        ctx.assertEquals(1, hits.get());
    }

    /**
     * Combines one connection-close failure with retryable 503 responses and asserts upstream and
     * HTTP policies share one counter, producing three total hits and final 503 body.
     */
    @Test
    public void testMixedFailuresShareCounterAcrossPolicyTypes(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"upstreamFailure\":2,\"503\":2}");
        AtomicInteger hits = new AtomicInteger();
        Async ready = ctx.async();
        int[] port = new int[1];
        backend = vertx.createHttpServer().requestHandler(req -> {
            int attempt = hits.incrementAndGet();
            if (attempt == 1) {
                req.connection().close();
            } else {
                req.bodyHandler(body -> req.response().setStatusCode(503).end("response-" + attempt));
            }
        });
        backend.listen(0, ctx.asyncAssertSuccess(server -> {
            port[0] = server.actualPort();
            ready.complete();
        }));
        ready.awaitSuccess(AWAIT_MS);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port[0], HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap());
        ctx.assertEquals(3, hits.get());
        ctx.assertEquals(503, response.status.get());
        ctx.assertEquals("response-3", response.body.toString());
    }

    /**
     * Two concurrent requests on a port-wildcard rule resolve different backends. The delayed retry
     * of the first request must stay on its own backend (and Host header), even though the second
     * request was resolved in the meantime.
     */
    @Test
    public void testRetryKeepsResolvedWildcardTargetDespiteConcurrentRequest(TestContext ctx) {
        AtomicInteger hitsA = new AtomicInteger();
        AtomicInteger hitsB = new AtomicInteger();
        java.util.List<String> hostsA = new java.util.concurrent.CopyOnWriteArrayList<>();
        Async firstAttemptReceived = ctx.async();
        int portA = listen(ctx, req -> {
            int attempt = hitsA.incrementAndGet();
            hostsA.add(req.getHeader("Host"));
            req.bodyHandler(body -> {
                if (attempt == 1) {
                    firstAttemptReceived.complete();
                    vertx.setTimer(300, ignored -> req.response().setStatusCode(503).end("A-1"));
                } else {
                    req.response().end("A-" + attempt);
                }
            });
        });
        int portB = listen(ctx, req -> {
            hitsB.incrementAndGet();
            req.bodyHandler(body -> req.response().end("B"));
        });
        Rule rule = buildRule(portA);
        rule.setUrlPattern("^/port/(\\d+)/resource$");
        rule.setPortWildcard("$1");
        rule.setPath(REQUEST_URI);
        rule.setPoolSize(2);
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));

        Async doneA = ctx.async();
        CapturingResponse responseA = new CapturingResponse(doneA);
        forwarder.handle(buildRoutingContext(HttpMethod.PUT, responseA,
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-503", "1"),
                "/port/" + portA + "/resource"), Buffer.buffer("payload"), null);
        firstAttemptReceived.awaitSuccess(AWAIT_MS);

        Async doneB = ctx.async();
        CapturingResponse responseB = new CapturingResponse(doneB);
        forwarder.handle(buildRoutingContext(HttpMethod.PUT, responseB, MultiMap.caseInsensitiveMultiMap(),
                "/port/" + portB + "/resource"), Buffer.buffer("payload"), null);
        doneB.awaitSuccess(AWAIT_MS);
        doneA.awaitSuccess(AWAIT_MS);

        ctx.assertEquals("B", responseB.body.toString());
        ctx.assertEquals(1, hitsB.get(), "The retry must not be sent to the backend of the concurrent request");
        ctx.assertEquals(2, hitsA.get());
        ctx.assertEquals(200, responseA.status.get());
        ctx.assertEquals("A-2", responseA.body.toString());
        for (String host : hostsA) {
            ctx.assertEquals("localhost:" + portA, host, "Every attempt must keep its own default Host header");
        }
    }

    /**
     * Sends retry headers plus a custom header to a backend that succeeds on retry and asserts the
     * retry headers are stripped upstream while this hop still performs two hits and keeps custom
     * headers.
     */
    @Test
    public void testRetryHeadersAreNotForwardedUpstream(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        java.util.List<String> leaked = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<String> custom = new java.util.concurrent.CopyOnWriteArrayList<>();
        int port = listen(ctx, req -> {
            int attempt = hits.incrementAndGet();
            req.headers().names().stream()
                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith("x-route-retry-"))
                    .forEach(leaked::add);
            custom.add(req.getHeader("x-custom"));
            req.bodyHandler(body -> req.response().setStatusCode(attempt == 1 ? 503 : 200).end("response-" + attempt));
        });
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("X-Route-Retry-503", "1").set("x-route-retry-5xx", "2")
                        .set("x-custom", "kept"));
        ctx.assertEquals(2, hits.get(), "The retry header must still be honoured by this hop");
        ctx.assertEquals(200, response.status.get());
        ctx.assertTrue(leaked.isEmpty(), "Retry headers leaked upstream: " + leaked);
        ctx.assertEquals(java.util.Arrays.asList("kept", "kept"), custom);
    }

    /**
     * Makes a routed request body read fail before forwarding while retry headers are present and
     * asserts the client receives one 400 response and the backend is never contacted.
     */
    @Test
    public void testRoutedBodyReadFailureRespondsBadRequest(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = listen(ctx, req -> {
            hits.incrementAndGet();
            req.response().end();
        });
        Rule rule = buildRule(port);
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
        Async done = ctx.async();
        CapturingResponse response = new CapturingResponse(done);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", "2");
        DummyHttpServerRequest request = new DummyHttpServerRequest() {
            @Override public HttpMethod method() { return HttpMethod.PUT; }
            @Override public String uri() { return REQUEST_URI; }
            @Override public String path() { return REQUEST_URI; }
            @Override public MultiMap headers() { return headers; }
            @Override public HttpServerResponse response() { return response; }
            @Override public boolean isEnded() { return false; }
            @Override public Future<Buffer> body() { return Future.failedFuture("connection reset"); }
            @Override public DummyHttpServerRequest pause() { return this; }
            @Override public DummyHttpServerRequest resume() { return this; }
            @Override public HttpConnection connection() { return null; }
        };
        RoutingContext routingContext = mock(RoutingContext.class);
        when(routingContext.request()).thenReturn(request);

        forwarder.handle(routingContext);
        done.awaitSuccess(AWAIT_MS);

        ctx.assertEquals(400, response.status.get());
        ctx.assertEquals(1, response.endCalls.get());
        ctx.assertEquals(0, hits.get(), "An incomplete body must never be forwarded");
    }

    /**
     * Regression against develop: an ordinary (non-retry) response is still logged once when the
     * client disconnects while the response is being streamed; asserts one log event and one hit.
     */
    @Test
    public void testResponseIsLoggedOnceWhenClientDisconnectsMidResponse(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startProgressiveBackend(ctx, hits, 200, null);
        LoggingResource resource = new LoggingResource();
        resource.addPayloadFilter(ImmutableMap.of("url", REQUEST_URI, "destination", "disconnect-regression"));
        resource.addFilterDestinations(ImmutableMap.of("disconnect-regression", ImmutableMap.of("address", "unused")));
        LoggingResourceManager manager = mock(LoggingResourceManager.class);
        when(manager.getLoggingResource()).thenReturn(resource);
        Async logged = ctx.async();
        AtomicInteger logCalls = new AtomicInteger();
        AbstractAppender appender = new AbstractAppender("disconnect-regression", null,
                PatternLayout.createDefaultLayout(), false, null) {
            @Override
            public void append(LogEvent event) {
                if (logCalls.incrementAndGet() == 1) {
                    logged.complete();
                }
            }
        };
        appender.start();
        LogAppenderRepository appenders = mock(LogAppenderRepository.class);
        when(appenders.hasAppender("disconnect-regression")).thenReturn(true);
        when(appenders.getAppender("disconnect-regression")).thenReturn(appender);
        org.apache.logging.log4j.core.Logger logger =
                (org.apache.logging.log4j.core.Logger) LogManager.getLogger("LOG_FILTER_" + REQUEST_URI);
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        Configurator.setLevel(logger.getName(), Level.INFO);
        try {
            Rule rule = buildRule(port);
            Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()),
                    manager, appenders, null);
            int proxyPort = startForwardingServer(ctx, forwarder, false);
            vertx.createHttpClient().request(HttpMethod.GET, proxyPort, "localhost", REQUEST_URI)
                    .compose(req -> req.send(Buffer.buffer("payload")))
                    .onComplete(ctx.asyncAssertSuccess(response -> {
                        ctx.assertEquals(200, response.statusCode());
                        response.request().connection().close();
                    }));
            logged.awaitSuccess(AWAIT_MS);
            Async settled = ctx.async();
            vertx.setTimer(300, ignored -> settled.complete());
            settled.awaitSuccess(AWAIT_MS);
            ctx.assertEquals(1, logCalls.get());
            ctx.assertEquals(1, hits.get());
        } finally {
            logger.removeAppender(appender);
            logger.setAdditive(previousAdditive);
            Configurator.setLevel(logger.getName(), previousLevel);
            appender.stop();
        }
    }

    // -------------------------------------------------------------------------
    // Retry outcome summary logs
    // -------------------------------------------------------------------------

    /**
     * Retries two upstream failures then succeeds and asserts three hits plus one INFO summary that
     * reports success after two retries with HTTP 200.
     */
    @Test
    public void testSuccessfulRetryLogsInfoSummary(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"upstreamFailure\":2}");
        AtomicInteger hits = new AtomicInteger();
        int port = startCountingBackend(ctx, hits, 2);

        List<String> logs = captureRetrySummaryLogs(() -> forwardBufferedGetAndAwaitStatus(ctx, port));

        ctx.assertEquals(3, hits.get());
        ctx.assertEquals(List.of("INFO Request GET http://localhost:" + port + REQUEST_URI
                + " succeeded after 2 retries with HTTP 200"), logs);
    }

    /**
     * Exhausts upstreamFailure retries against a connection-closing backend and asserts three hits
     * plus exactly one ERROR summary containing HTTP 502 and the upstream failure reason.
     */
    @Test
    public void testExhaustedUpstreamFailureRetriesLogErrorSummary(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"upstreamFailure\":2}");
        AtomicInteger hits = new AtomicInteger();
        int port = startCountingBackend(ctx, hits, Integer.MAX_VALUE);

        List<String> logs = captureRetrySummaryLogs(() -> forwardBufferedGetAndAwaitStatus(ctx, port));

        ctx.assertEquals(3, hits.get());
        ctx.assertEquals(1, logs.size(), "Exactly one summary expected: " + logs);
        ctx.assertTrue(logs.get(0).startsWith("ERROR Request GET http://localhost:" + port + REQUEST_URI
                + " failed after 2 retries with HTTP 502: Bad upstream response: "), logs.get(0));
    }

    /**
     * Exhausts per-request 5xx retries against repeated 503 responses and asserts three hits plus a
     * single ERROR summary that reports failure after two retries with HTTP 503.
     */
    @Test
    public void testExhaustedHttpStatusRetriesLogErrorSummary(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503);

        List<String> logs = captureRetrySummaryLogs(() -> forwardAndAwaitResponse(ctx, port, HttpMethod.PUT,
                Buffer.buffer("payload"), MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", "2")));

        ctx.assertEquals(3, hits.get());
        ctx.assertEquals(List.of("ERROR Request PUT http://localhost:" + port + REQUEST_URI
                + " failed after 2 retries with HTTP 503"), logs);
    }

    /**
     * Lets a large header budget be cut short by x-timeout and asserts two hits and exactly one
     * ERROR summary for failure after one retry, regardless of which timeout callback wins.
     */
    @Test
    public void testRetryDeadlineLogsSingleErrorSummary(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startDelayedFailureBackend(ctx, hits, true);

        List<String> logs = captureRetrySummaryLogs(() -> forwardAndAwaitResponse(ctx, port, HttpMethod.GET,
                Buffer.buffer("payload"), MultiMap.caseInsensitiveMultiMap()
                        .set("x-route-retry-5xx", "100").set("x-timeout", "1200")));

        ctx.assertEquals(2, hits.get());
        // Either the upstream idle timeout (clamped to the remaining budget) or the request-wide
        // deadline timer ends the request; both must produce exactly one ERROR, never a duplicate.
        ctx.assertEquals(1, logs.size(), "Exactly one ERROR expected: " + logs);
        ctx.assertTrue(logs.get(0).startsWith("ERROR Request GET http://localhost:" + port + REQUEST_URI
                + " failed after 1 retries with HTTP 502: "), logs.get(0));
    }

    /**
     * Sends a default non-retried upstream failure and asserts one hit and no retry summary log,
     * because summary logging is only emitted after actual retries.
     */
    @Test
    public void testRequestWithoutRetriesLogsNoSummary(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startCountingBackend(ctx, hits, Integer.MAX_VALUE);

        List<String> logs = captureRetrySummaryLogs(() -> forwardBufferedGetAndAwaitStatus(ctx, port));

        ctx.assertEquals(1, hits.get());
        ctx.assertEquals(List.of(), logs);
    }

    /**
     * Runs {@code action} while capturing INFO+ events of the {@link Forwarder} logger and returns
     * the retry summaries (and any other ERROR events) as {@code "<LEVEL> <message>"}. Summaries are
     * logged before the response is ended, so they are complete once the action returns.
     *
     * @param action action that triggers forwarding and waits for completion
     * @return captured retry summary or ERROR log lines
     */
    private List<String> captureRetrySummaryLogs(Supplier<?> action) {
        return captureForwarderLogs(action).stream()
                .filter(e -> e.startsWith("ERROR ") || e.matches("INFO Request \\S+ \\S+ succeeded after .*"))
                .collect(Collectors.toList());
    }

    /**
     * Runs {@code action} while capturing all INFO+ events of the {@link Forwarder} logger.
     *
     * @param action action that triggers forwarding and waits for completion
     * @return all captured events as {@code "<LEVEL> <message>"}, in logging order
     */
    private List<String> captureForwarderLogs(Supplier<?> action) {
        List<String> events = new CopyOnWriteArrayList<>();
        AbstractAppender appender = new AbstractAppender("retry-summary", null,
                PatternLayout.createDefaultLayout(), false, null) {
            @Override
            public void append(LogEvent event) {
                events.add(event.getLevel() + " " + event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        org.apache.logging.log4j.core.Logger logger =
                (org.apache.logging.log4j.core.Logger) LogManager.getLogger(Forwarder.class);
        Level previousLevel = logger.getLevel();
        Configurator.setLevel(logger.getName(), Level.INFO);
        logger.addAppender(appender);
        try {
            action.get();
        } finally {
            logger.removeAppender(appender);
            Configurator.setLevel(logger.getName(), previousLevel);
            appender.stop();
        }
        return new ArrayList<>(events);
    }

    // -------------------------------------------------------------------------
    // Connection failures (no connection established)
    // -------------------------------------------------------------------------

    /**
     * Targets a port without listener under {@code upstreamFailure:2} and asserts the refused
     * connection is retried twice (INFO per retry) before the client gets 503 with one ERROR summary.
     */
    @Test
    public void testConnectionRefusedIsRetriedAsUpstreamFailure(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"upstreamFailure\":2}");
        int port = unusedPort(ctx);

        AtomicReference<CapturingResponse> response = new AtomicReference<>();
        List<String> logs = captureForwarderLogs(() -> response.getAndSet(forwardAndAwaitResponse(ctx, port,
                HttpMethod.GET, Buffer.buffer("payload"), MultiMap.caseInsensitiveMultiMap())));

        ctx.assertEquals(503, response.get().status.get());
        ctx.assertEquals(1, response.get().endCalls.get());
        ctx.assertEquals(2L, logs.stream().filter(e -> e.startsWith("INFO Retrying request (attempt ")
                && e.endsWith(" after connection failure")).count(), "Retry logs: " + logs);
        ctx.assertEquals(1L, logs.stream().filter(e -> e.startsWith("ERROR Request GET http://localhost:" + port
                + REQUEST_URI + " failed after 2 retries with HTTP 503: Problem to request")).count(), "Summary: " + logs);
    }

    /**
     * Targets a port without listener and sends {@code x-route-retry-502: 1}; asserts the header
     * pattern for missing responses also covers refused connections (one retry, then 503).
     */
    @Test
    public void testConnectionRefusedIsRetriedBy502Header(TestContext ctx) {
        int port = unusedPort(ctx);

        AtomicReference<CapturingResponse> response = new AtomicReference<>();
        List<String> logs = captureForwarderLogs(() -> response.getAndSet(forwardAndAwaitResponse(ctx, port,
                HttpMethod.GET, Buffer.buffer("payload"), MultiMap.caseInsensitiveMultiMap().set("x-route-retry-502", "1"))));

        ctx.assertEquals(503, response.get().status.get());
        ctx.assertEquals(1L, logs.stream().filter(e -> e.endsWith(" after connection failure")).count(), "Retry logs: " + logs);
    }

    /**
     * Regression: targets a port without listener and without any retry configuration; asserts the
     * client gets 503 without a retry or retry summary, exactly as before.
     */
    @Test
    public void testConnectionRefusedWithoutPolicyIsNotRetried(TestContext ctx) {
        int port = unusedPort(ctx);

        AtomicReference<CapturingResponse> response = new AtomicReference<>();
        List<String> logs = captureForwarderLogs(() -> response.getAndSet(forwardAndAwaitResponse(ctx, port,
                HttpMethod.GET, Buffer.buffer("payload"), MultiMap.caseInsensitiveMultiMap())));

        ctx.assertEquals(503, response.get().status.get());
        ctx.assertEquals(1, response.get().endCalls.get());
        ctx.assertFalse(logs.stream().anyMatch(e -> e.contains("Retrying request") || e.contains(" retries with HTTP ")),
                "No retry expected: " + logs);
    }

    /**
     * Finds a local TCP port that currently has no listener, by binding and closing a server.
     *
     * @param ctx Vert.x unit context used to await bind and close
     * @return a port without listener
     */
    private int unusedPort(TestContext ctx) {
        Async closed = ctx.async();
        int[] port = new int[1];
        vertx.createHttpServer().requestHandler(req -> req.response().end()).listen(0, ctx.asyncAssertSuccess(server -> {
            port[0] = server.actualPort();
            server.close(ctx.asyncAssertSuccess(v -> closed.complete()));
        }));
        closed.awaitSuccess(AWAIT_MS);
        return port[0];
    }

    // -------------------------------------------------------------------------
    // After handler
    // -------------------------------------------------------------------------

    /**
     * Retries a 503 into a 200 and asserts the after handler (e.g. a hook registration) runs exactly
     * once, for the final successful response only.
     */
    @Test
    public void testAfterHandlerCalledOnceAfterSuccessfulRetry(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503, 200);
        AtomicInteger afterCalls = new AtomicInteger();

        CapturingResponse response = forwardWithAfterHandler(ctx, port, afterCalls,
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", "2"));

        ctx.assertEquals(2, hits.get());
        ctx.assertEquals(200, response.status.get());
        ctx.assertEquals(1, afterCalls.get());
    }

    /**
     * Exhausts the retries of a repeated 503 and asserts the after handler is never called, neither
     * for the drained intermediate responses nor for the final error.
     */
    @Test
    public void testAfterHandlerNotCalledWhenRetriesEndWithError(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503);
        AtomicInteger afterCalls = new AtomicInteger();

        CapturingResponse response = forwardWithAfterHandler(ctx, port, afterCalls,
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", "1"));

        ctx.assertEquals(2, hits.get());
        ctx.assertEquals(503, response.status.get());
        ctx.assertEquals(0, afterCalls.get());
    }

    /**
     * Forwards a buffered GET with the given headers and an after handler counting its calls, and
     * waits for the response; a short settle delay catches late after-handler calls.
     *
     * @param ctx Vert.x unit context used to await completion
     * @param backendPort backend port to target
     * @param afterCalls counter incremented by the after handler
     * @param headers request headers
     * @return response recorder completed by the forwarder
     */
    private CapturingResponse forwardWithAfterHandler(TestContext ctx, int backendPort, AtomicInteger afterCalls,
                                                      MultiMap headers) {
        Rule rule = buildRule(backendPort);
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
        Async done = ctx.async();
        CapturingResponse response = new CapturingResponse(done);
        forwarder.handle(buildRoutingContext(HttpMethod.GET, response, headers), Buffer.buffer("payload"),
                ignored -> afterCalls.incrementAndGet());
        done.awaitSuccess(AWAIT_MS);
        Async settled = ctx.async();
        vertx.setTimer(200, ignored -> settled.complete());
        settled.awaitSuccess(AWAIT_MS);
        return response;
    }

    /**
     * Regression against develop: the client disconnects while a 200 is being streamed and the
     * backend still completes it; asserts the after handler is called (once), as before.
     */
    @Test
    public void testAfterHandlerCalledWhenClientDisconnectsMidSuccessfulResponse(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startProgressiveBackend(ctx, hits, 200, null);
        Rule rule = buildRule(port);
        Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()));
        Async afterCalled = ctx.async();
        AtomicInteger afterCalls = new AtomicInteger();
        int proxyPort = startForwardingServer(ctx, forwarder, true, ignored -> {
            if (afterCalls.incrementAndGet() == 1) {
                afterCalled.complete();
            }
        });
        vertx.createHttpClient().request(HttpMethod.GET, proxyPort, "localhost", REQUEST_URI)
                .compose(req -> req.send(Buffer.buffer("payload")))
                .onComplete(ctx.asyncAssertSuccess(response -> {
                    ctx.assertEquals(200, response.statusCode());
                    response.request().connection().close();
                }));
        afterCalled.awaitSuccess(AWAIT_MS);
        Async settled = ctx.async();
        vertx.setTimer(200, ignored -> settled.complete());
        settled.awaitSuccess(AWAIT_MS);
        ctx.assertEquals(1, afterCalls.get());
        ctx.assertEquals(1, hits.get());
    }

    // -------------------------------------------------------------------------
    // Methods, precedence and ceiling
    // -------------------------------------------------------------------------

    /** Asserts HEAD is idempotent and therefore retried. */
    @Test
    public void testHeadIsRetried(TestContext ctx) {
        assertMethodRetryHits(ctx, HttpMethod.HEAD, 2);
    }

    /** Asserts DELETE is idempotent and therefore retried. */
    @Test
    public void testDeleteIsRetried(TestContext ctx) {
        assertMethodRetryHits(ctx, HttpMethod.DELETE, 2);
    }

    /** Asserts OPTIONS is idempotent and therefore retried. */
    @Test
    public void testOptionsIsRetried(TestContext ctx) {
        assertMethodRetryHits(ctx, HttpMethod.OPTIONS, 2);
    }

    /** Asserts TRACE is idempotent and therefore retried. */
    @Test
    public void testTraceIsRetried(TestContext ctx) {
        assertMethodRetryHits(ctx, HttpMethod.TRACE, 2);
    }

    /** Asserts PATCH is not idempotent and therefore never retried. */
    @Test
    public void testPatchIsNotRetried(TestContext ctx) {
        assertMethodRetryHits(ctx, HttpMethod.PATCH, 1);
    }

    /**
     * Sends a request with the given method and an empty buffered body against an always-503
     * backend with {@code x-route-retry-503: 1}, and asserts the number of backend hits.
     *
     * @param ctx Vert.x unit context used for assertions
     * @param method HTTP method to send
     * @param expectedHits expected backend hits including the initial attempt
     */
    private void assertMethodRetryHits(TestContext ctx, HttpMethod method, int expectedHits) {
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, method, Buffer.buffer(),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-503", "1"));
        ctx.assertEquals(expectedHits, hits.get(), method + " hits");
        ctx.assertEquals(503, response.status.get());
    }

    /**
     * Configures static {@code 429:3} and sends {@code x-route-retry-4xx: 0}; asserts the class
     * header overrides the exact static setting, so a 429 is not retried.
     */
    @Test
    public void testClassHeaderZeroOverridesStaticExact4xx(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"429\":3}");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 429);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-4xx", "0"));
        ctx.assertEquals(1, hits.get());
        ctx.assertEquals(429, response.status.get());
    }

    /**
     * Configures static {@code 4xx:2, 404:0}; asserts the exact static 0 disables retries for 404.
     */
    @Test
    public void testStaticExact4xxZeroOverridesStaticClass(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"4xx\":2,\"404\":0}");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 404);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap());
        ctx.assertEquals(1, hits.get());
        ctx.assertEquals(404, response.status.get());
    }

    /**
     * Configures static {@code 4xx:2, 404:0}; asserts a 400 still uses the static class budget
     * (three hits).
     */
    @Test
    public void testStaticClass4xxAppliesToOtherStatuses(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"4xx\":2,\"404\":0}");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 400);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap());
        ctx.assertEquals(3, hits.get());
        ctx.assertEquals(400, response.status.get());
    }

    /**
     * Sets the ceiling to 1 and sends {@code x-route-retry-4xx: 5}; asserts the ceiling also caps
     * 4xx header budgets (two hits).
     */
    @Test
    public void testCeilingCaps4xxHeaderRetries(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.MAX_RETRIES_PROPERTY, "1");
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 400);
        CapturingResponse response = forwardAndAwaitResponse(ctx, port, HttpMethod.GET, Buffer.buffer("payload"),
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-4xx", "5"));
        ctx.assertEquals(2, hits.get());
        ctx.assertEquals(400, response.status.get());
    }

    // -------------------------------------------------------------------------
    // Timeouts and logging
    // -------------------------------------------------------------------------

    /**
     * Regression: {@code x-timeout: 0} without any retry configuration still disables the timeout,
     * so a long progressive 200 completes in one hit.
     */
    @Test
    public void testDisabledTimeoutWithoutRetriesAllowsProgressiveResponse(TestContext ctx) {
        assertProgressiveResponseSucceeds(ctx, HttpMethod.GET, true,
                MultiMap.caseInsensitiveMultiMap().set("x-timeout", "0"));
    }

    /**
     * Lets the retry deadline expire while a 200 is being streamed and asserts the received response
     * is still request-logged exactly once and the expiry is logged as ERROR.
     */
    @Test
    public void testDeadlineExpiryMidResponseLogsResponseOnce(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        Async closed = ctx.async();
        int port = startProgressiveBackend(ctx, hits, 200, closed);
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"5xx\":2}");
        List<String> forwarderLogs = new CopyOnWriteArrayList<>();
        int requestLogs = countRequestLogEvents(ctx, (manager, appenders) -> forwarderLogs.addAll(captureForwarderLogs(() -> {
            Rule rule = buildRule(port);
            rule.setTimeout(600);
            Forwarder forwarder = buildForwarder(rule, vertx.createHttpClient(rule.buildHttpClientOptions()),
                    manager, appenders, null);
            int proxyPort = startForwardingServer(ctx, forwarder, true);
            Async done = ctx.async();
            vertx.createHttpClient().request(HttpMethod.GET, proxyPort, "localhost", REQUEST_URI)
                    .compose(req -> req.send(Buffer.buffer("payload")))
                    .onComplete(ctx.asyncAssertSuccess(response -> {
                        ctx.assertEquals(200, response.statusCode());
                        response.body().onComplete(result -> {
                            ctx.assertTrue(result.failed(), "Partial response must be closed");
                            done.complete();
                        });
                    }));
            done.awaitSuccess(AWAIT_MS);
            return null;
        })));
        closed.awaitSuccess(AWAIT_MS);
        ctx.assertEquals(1, requestLogs, "The received response must be logged exactly once");
        ctx.assertEquals(1, hits.get());
        ctx.assertTrue(forwarderLogs.stream().anyMatch(e -> e.startsWith("ERROR http://localhost:" + port + REQUEST_URI
                + " ") && e.contains("timeout")), "Expiry ERROR expected: " + forwarderLogs);
    }

    /**
     * Runs {@code action} with a request-logging configuration (payload filter on
     * {@link #REQUEST_URI}) and returns how many request log events were written, after waiting
     * for the first one and a short settle delay for duplicates.
     *
     * @param ctx Vert.x unit context used to await the log events
     * @param action receives the logging manager and appender repository to build the forwarder with
     * @return number of request log events
     */
    private int countRequestLogEvents(TestContext ctx, BiConsumer<LoggingResourceManager, LogAppenderRepository> action) {
        LoggingResource resource = new LoggingResource();
        resource.addPayloadFilter(ImmutableMap.of("url", REQUEST_URI, "destination", "request-log-count"));
        resource.addFilterDestinations(ImmutableMap.of("request-log-count", ImmutableMap.of("address", "unused")));
        LoggingResourceManager manager = mock(LoggingResourceManager.class);
        when(manager.getLoggingResource()).thenReturn(resource);
        Async logged = ctx.async();
        AtomicInteger logCalls = new AtomicInteger();
        AbstractAppender appender = new AbstractAppender("request-log-count", null,
                PatternLayout.createDefaultLayout(), false, null) {
            @Override
            public void append(LogEvent event) {
                if (logCalls.incrementAndGet() == 1) {
                    logged.complete();
                }
            }
        };
        appender.start();
        LogAppenderRepository appenders = mock(LogAppenderRepository.class);
        when(appenders.hasAppender("request-log-count")).thenReturn(true);
        when(appenders.getAppender("request-log-count")).thenReturn(appender);
        org.apache.logging.log4j.core.Logger logger =
                (org.apache.logging.log4j.core.Logger) LogManager.getLogger("LOG_FILTER_" + REQUEST_URI);
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        Configurator.setLevel(logger.getName(), Level.INFO);
        try {
            action.accept(manager, appenders);
            logged.awaitSuccess(AWAIT_MS);
            Async settled = ctx.async();
            vertx.setTimer(300, ignored -> settled.complete());
            settled.awaitSuccess(AWAIT_MS);
            return logCalls.get();
        } finally {
            logger.removeAppender(appender);
            logger.setAdditive(previousAdditive);
            Configurator.setLevel(logger.getName(), previousLevel);
            appender.stop();
        }
    }

    /**
     * Retries a 503 that is translated to 200 for the client and asserts the INFO summary reports
     * the backend status and notes the status the client received.
     */
    @Test
    public void testRetrySummaryNotesTranslatedClientStatus(TestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        int port = startStatusBackend(ctx, hits, 503, 404);

        List<String> logs = captureRetrySummaryLogs(() -> forwardAndAwaitResponse(ctx, port, HttpMethod.GET,
                Buffer.buffer("payload"), MultiMap.caseInsensitiveMultiMap().set("x-route-retry-503", "1")
                        .set("x-translate-status-404", "200")));

        ctx.assertEquals(2, hits.get());
        ctx.assertEquals(List.of("ERROR Request GET http://localhost:" + port + REQUEST_URI
                + " failed after 1 retries with HTTP 404 (client received HTTP 200)"), logs);
    }

    /**
     * Starts a backend server using the given request handler and waits until it is listening.
     *
     * @param ctx Vert.x unit context used to await the listen result
     * @param handler backend request handler to install
     * @return actual listen port
     */
    private int listen(TestContext ctx, Handler<io.vertx.core.http.HttpServerRequest> handler) {
        Async ready = ctx.async();
        int[] port = new int[1];
        vertx.createHttpServer().requestHandler(handler).listen(0, ctx.asyncAssertSuccess(server -> {
            port[0] = server.actualPort();
            ready.complete();
        }));
        ready.awaitSuccess(AWAIT_MS);
        return port[0];
    }
}

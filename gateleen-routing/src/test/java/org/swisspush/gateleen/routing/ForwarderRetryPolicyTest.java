package org.swisspush.gateleen.routing;

import com.google.common.collect.ImmutableMap;
import io.vertx.core.MultiMap;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Exercises retry configuration and precedence without starting Vert.x or an HTTP backend.
 */
@RunWith(VertxUnitRunner.class)
public class ForwarderRetryPolicyTest {

    private String previousStatuses;
    private String previousCeiling;
    private String previousBodyLimit;

    /**
     * Saves the retry-related system properties and clears them so each policy test starts from the
     * documented default configuration.
     */
    @Before
    public void setUp() {
        previousStatuses = System.getProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY);
        previousCeiling = System.getProperty(ForwarderRetryPolicy.MAX_RETRIES_PROPERTY);
        previousBodyLimit = System.getProperty(ForwarderRetryPolicy.MAX_RETRY_BODY_BYTES_PROPERTY);
        System.clearProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY);
        System.clearProperty(ForwarderRetryPolicy.MAX_RETRIES_PROPERTY);
        System.clearProperty(ForwarderRetryPolicy.MAX_RETRY_BODY_BYTES_PROPERTY);
    }

    /**
     * Restores the retry-related system properties captured by {@link #setUp()} to avoid leaking
     * configuration into other test classes.
     */
    @After
    public void tearDown() {
        restoreProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, previousStatuses);
        restoreProperty(ForwarderRetryPolicy.MAX_RETRIES_PROPERTY, previousCeiling);
        restoreProperty(ForwarderRetryPolicy.MAX_RETRY_BODY_BYTES_PROPERTY, previousBodyLimit);
    }

    /**
     * Restores or clears one system property after a test.
     *
     * @param name property name to restore
     * @param previousValue previous value, or {@code null} when the property was absent
     */
    private void restoreProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }

    /**
     * Verifies that retry headers for this hop are stripped while similarly named non-retry and
     * custom headers remain, preventing retry policy leakage upstream.
     */
    @Test
    public void testRemoveRetryHeadersOnlyRemovesRetryHeaders(TestContext ctx) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                .add("X-Route-Retry-503", "1")
                .add("x-route-retry-5xx", "2")
                .add("x-route-retry-upstreamFailure", "3")
                .add("x-queue-retry-5xx", "4")
                .add("x-custom", "kept");
        ForwarderRetryPolicy.removeRetryHeaders(headers);
        ctx.assertEquals(2, headers.size());
        ctx.assertEquals("4", headers.get("x-queue-retry-5xx"));
        ctx.assertEquals("kept", headers.get("x-custom"));
    }

    /**
     * Configures static exact, class and upstream-failure policies and asserts exact headers beat
     * class/static settings, invalid exact headers disable that match, and attempts include the
     * initial request.
     */
    @Test
    public void testPrecedenceAndFallback(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"5xx\":2,\"503\":0,\"upstreamFailure\":4}");
        ForwarderRetryPolicy policy = ForwarderRetryPolicy.fromSystemProperties();
        Logger log = mock(Logger.class);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        ctx.assertEquals(1L, policy.maxAttemptsForResponse(headers, 503, log));
        ctx.assertEquals(5L, policy.maxAttemptsForUpstreamFailure(headers, log));
        ctx.assertEquals(1L, policy.maxAttemptsForResponse(headers, 400, log));
        headers.set("x-route-retry-5xx", "3");
        ctx.assertEquals(4L, policy.maxAttemptsForResponse(headers, 503, log));
        headers.set("x-route-retry-503", "0");
        ctx.assertEquals(1L, policy.maxAttemptsForResponse(headers, 503, log));
        headers.set("x-route-retry-503", "invalid");
        ctx.assertEquals(1L, policy.maxAttemptsForResponse(headers, 503, log));
    }

    /**
     * Checks that {@code permitsRetries} reflects static policies, per-request overrides and the
     * max-retry ceiling so routed requests are buffered only when an effective retry budget exists.
     */
    @Test
    public void testPermitsRetriesUsesEffectiveOverridesAndCeiling(TestContext ctx) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        ctx.assertFalse(ForwarderRetryPolicy.fromSystemProperties().permitsRetries(headers));
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"5xx\":2,\"upstreamFailure\":2}");
        ForwarderRetryPolicy policy = ForwarderRetryPolicy.fromSystemProperties();
        ctx.assertTrue(policy.permitsRetries(headers));
        headers.set("x-route-retry-5xx", "0");
        ctx.assertFalse(policy.permitsRetries(headers), "Zero override disables both 5xx and upstreamFailure");
        headers.set("x-route-retry-503", "1");
        ctx.assertTrue(policy.permitsRetries(headers), "An exact positive exception still enables retries");
        headers.set("x-route-retry-503", "invalid");
        ctx.assertTrue(policy.permitsRetries(headers), "Invalid exact header restores the static 5xx budget");
        System.setProperty(ForwarderRetryPolicy.MAX_RETRIES_PROPERTY, "0");
        ctx.assertFalse(ForwarderRetryPolicy.fromSystemProperties().permitsRetries(headers));
    }

    /**
     * Ensures upstream-failure and received-502 matching stay separate: a zero 502 header disables
     * retries until another positive HTTP-status override makes the request retryable.
     */
    @Test
    public void testPermitsRetriesDoesNotConfuseUpstreamAndReceived502(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"upstreamFailure\":2,\"502\":1}");
        ForwarderRetryPolicy policy = ForwarderRetryPolicy.fromSystemProperties();
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().set("x-route-retry-502", "0");
        ctx.assertFalse(policy.permitsRetries(headers));
        headers.set("x-route-retry-400", "1");
        ctx.assertTrue(policy.permitsRetries(headers));
    }

    /**
     * Uses maximum integer retry counts to prove policies snapshot system properties at construction
     * and convert additional retries into attempt counts without overflow.
     */
    @Test
    public void testSnapshotAndMaximumCount(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.MAX_RETRIES_PROPERTY, "2147483647");
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"5xx\":2147483647}");
        ForwarderRetryPolicy policy = ForwarderRetryPolicy.fromSystemProperties();
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"5xx\":0}");
        Logger log = mock(Logger.class);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        ctx.assertEquals(2147483648L, policy.maxAttemptsForResponse(headers, 503, log));
        headers.set("x-route-retry-503", "2147483647");
        ctx.assertEquals(2147483648L, policy.maxAttemptsForResponse(headers, 503, log));
    }

    /**
     * Configures oversized static/header retry counts and asserts the default ceiling caps every
     * source at six attempts while smaller exact headers still win.
     */
    @Test
    public void testDefaultCeilingCapsAllSources(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"503\":100,\"upstreamFailure\":100}");
        ForwarderRetryPolicy policy = ForwarderRetryPolicy.fromSystemProperties();
        Logger log = mock(Logger.class);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        ctx.assertEquals(6L, policy.maxAttemptsForUpstreamFailure(headers, log));
        ctx.assertEquals(6L, policy.maxAttemptsForResponse(headers, 503, log));
        headers.set("x-route-retry-5xx", "2147483647");
        ctx.assertEquals(6L, policy.maxAttemptsForResponse(headers, 503, log));
        headers.set("x-route-retry-503", "2");
        ctx.assertEquals(3L, policy.maxAttemptsForResponse(headers, 503, log));
    }

    /**
     * Validates max-retry ceiling parsing: missing and invalid values use the default of five,
     * while trimmed non-negative integers are accepted.
     */
    @Test
    public void testCeilingParsing(TestContext ctx) {
        ctx.assertEquals(5, ForwarderRetryPolicy.resolveMaxRetries());
        for (String invalid : new String[]{"", "invalid", "-1", "1.5", "2147483648"}) {
            System.setProperty(ForwarderRetryPolicy.MAX_RETRIES_PROPERTY, invalid);
            ctx.assertEquals(5, ForwarderRetryPolicy.resolveMaxRetries());
        }
        System.setProperty(ForwarderRetryPolicy.MAX_RETRIES_PROPERTY, " 0 ");
        ctx.assertEquals(0, ForwarderRetryPolicy.resolveMaxRetries());
        System.setProperty(ForwarderRetryPolicy.MAX_RETRIES_PROPERTY, " 8 ");
        ctx.assertEquals(8, ForwarderRetryPolicy.resolveMaxRetries());
    }

    /**
     * Sets the ceiling to zero and asserts static and header retry budgets are all reduced to the
     * single initial attempt.
     */
    @Test
    public void testZeroCeilingDisablesEverySource(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.MAX_RETRIES_PROPERTY, "0");
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"503\":100,\"upstreamFailure\":100}");
        ForwarderRetryPolicy policy = ForwarderRetryPolicy.fromSystemProperties();
        Logger log = mock(Logger.class);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        ctx.assertEquals(1L, policy.maxAttemptsForUpstreamFailure(headers, log));
        ctx.assertEquals(1L, policy.maxAttemptsForResponse(headers, 503, log));
        headers.set("x-route-retry-5xx", "100");
        ctx.assertEquals(1L, policy.maxAttemptsForResponse(headers, 503, log));
    }

    /**
     * Builds a policy with a custom ceiling and then changes the property, asserting the original
     * ceiling was snapshotted and caps a huge header value at nine attempts.
     */
    @Test
    public void testCustomCeilingIsSnapshotted(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.MAX_RETRIES_PROPERTY, "8");
        ForwarderRetryPolicy policy = ForwarderRetryPolicy.fromSystemProperties();
        System.setProperty(ForwarderRetryPolicy.MAX_RETRIES_PROPERTY, "0");
        ctx.assertEquals(9L, policy.maxAttemptsForResponse(
                MultiMap.caseInsensitiveMultiMap().set("x-route-retry-5xx", "100"), 503, mock(Logger.class)));
    }

    /**
     * Exercises upstream-failure precedence: static HTTP policies do not apply, header 5xx can
     * override upstreamFailure, header 502 wins, and an invalid exact header falls back to static
     * upstreamFailure.
     */
    @Test
    public void testUpstreamFailurePolicyAndHeaders(TestContext ctx) {
        Logger log = mock(Logger.class);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        ctx.assertEquals(1L, ForwarderRetryPolicy.fromSystemProperties().maxAttemptsForUpstreamFailure(headers, log));
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"502\":3,\"5xx\":4}");
        ctx.assertEquals(1L, ForwarderRetryPolicy.fromSystemProperties().maxAttemptsForUpstreamFailure(headers, log),
                "Static HTTP policies must not enable retries when no response arrives");
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"upstreamFailure\":2,\"502\":0}");
        ForwarderRetryPolicy policy = ForwarderRetryPolicy.fromSystemProperties();
        ctx.assertEquals(3L, policy.maxAttemptsForUpstreamFailure(headers, log));
        ctx.assertEquals(1L, policy.maxAttemptsForResponse(headers, 502, log));
        headers.set("x-route-retry-5xx", "4");
        ctx.assertEquals(5L, policy.maxAttemptsForUpstreamFailure(headers, log));
        headers.set("x-route-retry-502", "0");
        ctx.assertEquals(1L, policy.maxAttemptsForUpstreamFailure(headers, log));
        headers.set("x-route-retry-502", "invalid");
        ctx.assertEquals(3L, policy.maxAttemptsForUpstreamFailure(headers, log),
                "An invalid exact header skips the class header and uses upstreamFailure");
    }

    /**
     * Parses upstreamFailure configuration case-insensitively and ignores invalid retry counts while
     * keeping valid entries, preserving only usable budgets.
     */
    @Test
    public void testUpstreamFailureParsing(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"UpstreamFailure\":2}");
        ctx.assertEquals(ImmutableMap.of("upstreamfailure", 2), ForwarderRetryPolicy.resolveRetriesByStatus());
        for (String invalid : new String[]{"-1", "\"3\"", "1.5", "2147483648", "null", "true"}) {
            System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY,
                    "{\"upstreamFailure\":" + invalid + ",\"503\":2}");
            ctx.assertEquals(ImmutableMap.of("503", 2), ForwarderRetryPolicy.resolveRetriesByStatus());
        }
    }

    /**
     * Resolves per-request retry headers with class/exact precedence, trimming, invalid-value
     * warnings, maximum integer support and status-class mismatch handling.
     */
    @Test
    public void testRouteRetryCountParsing(TestContext ctx) {
        Logger log = mock(Logger.class);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        ctx.assertNull(ForwarderRetryPolicy.resolveRouteRetryCount(headers, 404, log));
        headers.set("X-Route-Retry-4XX", " 3 ");
        ctx.assertEquals(3, ForwarderRetryPolicy.resolveRouteRetryCount(headers, 404, log));
        headers.set("x-route-retry-404", "0");
        ctx.assertEquals(0, ForwarderRetryPolicy.resolveRouteRetryCount(headers, 404, log));
        headers.set("x-route-retry-404", "-1");
        ctx.assertNull(ForwarderRetryPolicy.resolveRouteRetryCount(headers, 404, log));
        verify(log).warn("Ignoring invalid {}=\"{}\" (must be a non-negative integer)", "x-route-retry-404", "-1");
        for (String invalid : new String[]{"", "banana", "2147483648"}) {
            headers.set("x-route-retry-404", invalid);
            ctx.assertNull(ForwarderRetryPolicy.resolveRouteRetryCount(headers, 404, log));
        }
        headers.set("x-route-retry-404", "2147483647");
        ctx.assertEquals(Integer.MAX_VALUE, ForwarderRetryPolicy.resolveRouteRetryCount(headers, 404, log));
        ctx.assertNull(ForwarderRetryPolicy.resolveRouteRetryCount(headers, 503, log));
    }

    /**
     * Verifies a valid JSON policy normalizes class keys to lowercase and accepts exact statuses and
     * maximum integer retry counts.
     */
    @Test
    public void testResolveRetriesByStatus_validPolicy(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"4xx\":0,\"429\":3,\"5XX\":2147483647}");
        ctx.assertEquals(ImmutableMap.of("4xx", 0, "429", 3, "5xx", Integer.MAX_VALUE),
                ForwarderRetryPolicy.resolveRetriesByStatus());
    }

    /**
     * Confirms the built-in explicit default disables retries, allows request headers to opt in,
     * and that an explicit empty JSON object replaces rather than merges with defaults.
     */
    @Test
    public void testExplicitDefaultPolicyDisablesRetries(TestContext ctx) {
        ctx.assertEquals(ImmutableMap.of("upstreamfailure", 0, "4xx", 0, "5xx", 0, "502", 0),
                ForwarderRetryPolicy.resolveRetriesByStatus());
        ForwarderRetryPolicy policy = ForwarderRetryPolicy.fromSystemProperties();
        Logger log = mock(Logger.class);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        ctx.assertEquals(1L, policy.maxAttemptsForUpstreamFailure(headers, log));
        for (int status = 400; status < 600; status++) {
            ctx.assertEquals(1L, policy.maxAttemptsForResponse(headers, status, log));
        }
        headers.set("x-route-retry-5xx", "2");
        ctx.assertEquals(3L, policy.maxAttemptsForResponse(headers, 503, log));
        ctx.assertEquals(3L, policy.maxAttemptsForUpstreamFailure(headers, log));
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{}");
        ctx.assertTrue(ForwarderRetryPolicy.resolveRetriesByStatus().isEmpty(),
                "An explicit configuration replaces, rather than merges with, the default");
    }

    /**
     * Supplies malformed or non-object policy strings and asserts they resolve to an empty retry map
     * instead of enabling retries.
     */
    @Test
    public void testResolveRetriesByStatus_invalidJson(TestContext ctx) {
        for (String invalid : new String[]{"", "invalid", "[]", "null", "{\"5xx\":"}) {
            System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, invalid);
            ctx.assertTrue(ForwarderRetryPolicy.resolveRetriesByStatus().isEmpty());
        }
    }

    /**
     * Mixes valid and invalid policy entries and asserts invalid counts/status patterns are ignored
     * while the valid 5xx budget remains.
     */
    @Test
    public void testResolveRetriesByStatus_invalidEntriesAreIgnored(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY,
                "{\"5xx\":2,\"400\":-1,\"401\":\"3\",\"402\":1.5,\"403\":2147483648,"
                        + "\"404\":null,\"405\":true,\"2xx\":2,\"50x\":2,\"600\":2}");
        ctx.assertEquals(ImmutableMap.of("5xx", 2), ForwarderRetryPolicy.resolveRetriesByStatus());
    }

    /**
     * Compares static and per-request retry count validation for boundary and invalid values so both
     * configuration sources accept and reject the same budgets.
     */
    @Test
    public void testRetryCountValidation_isConsistentAcrossSources(TestContext ctx) {
        Logger log = mock(Logger.class);
        for (String value : new String[]{"0", "3", "2147483647", "-1", "2147483648", "1.5"}) {
            System.setProperty(ForwarderRetryPolicy.RETRIES_BY_STATUS_PROPERTY, "{\"503\":" + value + "}");
            Integer configured = ForwarderRetryPolicy.resolveRetriesByStatus().get("503");
            Integer perRequest = ForwarderRetryPolicy.resolveRouteRetryCount(
                    MultiMap.caseInsensitiveMultiMap().set("x-route-retry-503", value), 503, log);
            ctx.assertEquals(configured, perRequest, "Both sources must validate retry count " + value + " identically");
        }
    }

    /**
     * Verifies {@code maxRetryBodyBytes} defaults to 1 MiB when absent or invalid (non-numeric,
     * negative, overflowing, empty) and accepts trimmed non-negative values including 0.
     */
    @Test
    public void testMaxRetryBodyBytes_defaultsAndValidation(TestContext ctx) {
        ctx.assertEquals(1024L * 1024L, ForwarderRetryPolicy.resolveMaxRetryBodyBytes());
        for (String invalid : new String[]{"", "abc", "-1", "1.5", "9223372036854775808"}) {
            System.setProperty(ForwarderRetryPolicy.MAX_RETRY_BODY_BYTES_PROPERTY, invalid);
            ctx.assertEquals(1024L * 1024L,
                    ForwarderRetryPolicy.resolveMaxRetryBodyBytes(), "Invalid value " + invalid);
        }
        System.setProperty(ForwarderRetryPolicy.MAX_RETRY_BODY_BYTES_PROPERTY, " 0 ");
        ctx.assertEquals(0L, ForwarderRetryPolicy.resolveMaxRetryBodyBytes());
        System.setProperty(ForwarderRetryPolicy.MAX_RETRY_BODY_BYTES_PROPERTY, "2048");
        ctx.assertEquals(2048L, ForwarderRetryPolicy.resolveMaxRetryBodyBytes());
    }

    /**
     * Verifies {@code mayBufferBody} accepts declared lengths from 0 up to and including the limit
     * and rejects larger and negative lengths; a limit of 0 only allows empty bodies.
     */
    @Test
    public void testMayBufferBody_respectsLimit(TestContext ctx) {
        System.setProperty(ForwarderRetryPolicy.MAX_RETRY_BODY_BYTES_PROPERTY, "10");
        ForwarderRetryPolicy policy = ForwarderRetryPolicy.fromSystemProperties();
        ctx.assertTrue(policy.mayBufferBody(0));
        ctx.assertTrue(policy.mayBufferBody(10));
        ctx.assertFalse(policy.mayBufferBody(11));
        ctx.assertFalse(policy.mayBufferBody(-1));
        System.setProperty(ForwarderRetryPolicy.MAX_RETRY_BODY_BYTES_PROPERTY, "0");
        policy = ForwarderRetryPolicy.fromSystemProperties();
        ctx.assertTrue(policy.mayBufferBody(0));
        ctx.assertFalse(policy.mayBufferBody(1));
    }
}

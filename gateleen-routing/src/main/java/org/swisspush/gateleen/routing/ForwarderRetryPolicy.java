package org.swisspush.gateleen.routing;

import io.vertx.core.MultiMap;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import static org.swisspush.gateleen.core.util.StatusCode.BAD_GATEWAY;

/**
 * Immutable startup retry settings with per-request overrides.
 * Determines permitted attempt counts; replay safety and retry execution belong to {@link Forwarder}.
 */
public final class ForwarderRetryPolicy {

    /**
     * Startup-only JSON object mapping HTTP error statuses or classes to additional retry counts,
     * for example {@code {"upstreamFailure":2,"429":3,"5xx":1}}. The special upstreamFailure
     * entry applies when no HTTP response arrives, independently of HTTP status settings.
     * Request headers override this policy. A value of 3 means up to four total attempts.
     * The resolved count remains subject to {@link #MAX_RETRIES_PROPERTY}.
     * When absent, {@link #DEFAULT_RETRIES_BY_STATUS} explicitly disables retries for all cases.
     */
    public static final String RETRIES_BY_STATUS_PROPERTY =
            "org.swisspush.gateleen.routing.forwarder.retriesByStatus";

    /**
     * Startup-only ceiling on additional retries from every policy source.
     * Defaults to 5; zero disables all retries. Request headers cannot override this ceiling.
     * This is a safety limit, not a fallback retry policy: setting it to 5 permits at most six
     * total attempts but does not enable retries when the selected policy allows none.
     */
    public static final String MAX_RETRIES_PROPERTY =
            "org.swisspush.gateleen.routing.forwarder.maxRetries";

    /**
     * Startup-only maximum size in bytes of a routed request body that may be buffered in memory
     * to make it replayable for retries. Defaults to 1 MiB. Larger bodies, and bodies of unknown
     * length (chunked), are streamed as before and therefore never retried. Bodies already buffered
     * by the caller (e.g. hooks) are not affected.
     */
    public static final String MAX_RETRY_BODY_BYTES_PROPERTY =
            "org.swisspush.gateleen.routing.forwarder.maxRetryBodyBytes";

    private static final int DEFAULT_MAX_RETRIES = 5;
    private static final long DEFAULT_MAX_RETRY_BODY_BYTES = 1024L * 1024L;
    /**
     * Default JSON policy when no configuration is supplied. Class entries cover all received
     * HTTP errors; 502 explicitly documents received Bad Gateway responses, while upstreamFailure
     * covers failures without a response. Supplied JSON replaces these defaults so the explicit
     * 502 entry cannot mask a supplied 5xx policy.
     */
    public static final String DEFAULT_RETRIES_BY_STATUS =
            "{\"upstreamFailure\":0,\"4xx\":0,\"5xx\":0,\"502\":0}";

    private static final String UPSTREAM_FAILURE_PATTERN = "upstreamfailure";

    /**
     * Prefix for per-request retry overrides, followed by an exact HTTP error status (e.g. 503)
     * or status class (e.g. 5xx). Values specify additional retries and override the static policy.
     */
    private static final String ROUTE_RETRY_HEADER_PREFIX = "x-route-retry-";

    private static final Logger LOG = LoggerFactory.getLogger(ForwarderRetryPolicy.class);

    /**
     * Startup snapshot of ADDITIONAL retry counts keyed by exact error status (e.g. "503") or
     * class (e.g. "5xx"), or "upstreamfailure" for failures without a response.
     * Exact statuses override classes; valid request headers override static settings.
     * A missing setting means no retries.
     */
    private final Map<String, Integer> retriesByStatus;
    /** Ceiling on ADDITIONAL retries, applied after selecting the request or static budget. */
    private final int maxRetries;
    /** Maximum routed body size that may be buffered for replay, see {@link #MAX_RETRY_BODY_BYTES_PROPERTY}. */
    private final long maxRetryBodyBytes;

    /**
     * @param retriesByStatus   snapshot of additional retries keyed by lower-case status, class or
     *                          {@code upstreamfailure}
     * @param maxRetries        ceiling on additional retries from any source
     * @param maxRetryBodyBytes maximum routed body size in bytes that may be buffered for replay
     */
    private ForwarderRetryPolicy(Map<String, Integer> retriesByStatus, int maxRetries, long maxRetryBodyBytes) {
        this.retriesByStatus = retriesByStatus;
        this.maxRetries = maxRetries;
        this.maxRetryBodyBytes = maxRetryBodyBytes;
    }

    /**
     * Snapshots all retry system properties once per forwarder, rather than per request.
     * Set them before startup and do not change them at runtime: existing forwarders retain their
     * snapshot, while forwarders created later (e.g. after a rule change) would see new values.
     *
     * @return a new immutable policy built from {@link #RETRIES_BY_STATUS_PROPERTY},
     * {@link #MAX_RETRIES_PROPERTY} and {@link #MAX_RETRY_BODY_BYTES_PROPERTY}
     */
    public static ForwarderRetryPolicy fromSystemProperties() {
        return new ForwarderRetryPolicy(resolveRetriesByStatus(), resolveMaxRetries(), resolveMaxRetryBodyBytes());
    }

    /**
     * @return a policy that never retries, regardless of system properties and request headers
     */
    static ForwarderRetryPolicy disabled() {
        return new ForwarderRetryPolicy(Map.of(), 0, 0);
    }

    /**
     * Reads the buffering limit from {@link #MAX_RETRY_BODY_BYTES_PROPERTY}. An absent or invalid
     * value (negative or non-numeric) is logged and replaced with the default of 1 MiB.
     *
     * @return the maximum body size in bytes that may be buffered for replay; {@code 0} allows
     * only requests without a body to be buffered
     */
    static long resolveMaxRetryBodyBytes() {
        String property = System.getProperty(MAX_RETRY_BODY_BYTES_PROPERTY);
        if (property == null) {
            return DEFAULT_MAX_RETRY_BODY_BYTES;
        }
        try {
            long bytes = Long.parseLong(property.trim());
            if (bytes >= 0) {
                return bytes;
            }
        } catch (NumberFormatException e) {
            // logged below
        }
        LOG.warn("Ignoring invalid {}=\"{}\" (must be a non-negative integer), using {} instead",
                MAX_RETRY_BODY_BYTES_PROPERTY, property, DEFAULT_MAX_RETRY_BODY_BYTES);
        return DEFAULT_MAX_RETRY_BODY_BYTES;
    }

    /**
     * Tells whether a routed request body with the given declared length may be buffered for replay.
     *
     * @param contentLength the declared body length in bytes ({@code 0} for requests without a body)
     * @return {@code true} if {@code contentLength} is between 0 and the configured limit (inclusive)
     */
    boolean mayBufferBody(long contentLength) {
        return contentLength >= 0 && contentLength <= maxRetryBodyBytes;
    }

    /**
     * Reads the retry ceiling from {@link #MAX_RETRIES_PROPERTY}. An absent or invalid value
     * (negative, non-numeric or overflowing) is logged and replaced with the default of 5.
     *
     * @return the maximum number of additional retries, {@code 0} disables all retries
     */
    static int resolveMaxRetries() {
        String property = System.getProperty(MAX_RETRIES_PROPERTY);
        if (property == null) {
            return DEFAULT_MAX_RETRIES;
        }
        Integer retries = parseRetryCount(property);
        if (retries != null) {
            return retries;
        }
        LOG.warn("Ignoring invalid {}=\"{}\" (must be a non-negative integer), using {} instead",
                MAX_RETRIES_PROPERTY, property, DEFAULT_MAX_RETRIES);
        return DEFAULT_MAX_RETRIES;
    }

    /**
     * Returns total permitted attempts for a received HTTP response. Request headers override
     * static status settings; exact statuses beat classes within each source.
     * A missing policy means no retries. The ceiling caps the selected retry count.
     *
     * @param requestHeaders headers of the incoming request, may contain {@code x-route-retry-*} overrides
     * @param statusCode     original backend status, before translation
     * @param log            logger for invalid override headers, or {@code null} to suppress the warning
     * @return total permitted attempts including the initial one (always {@code >= 1})
     */
    public long maxAttemptsForResponse(MultiMap requestHeaders, int statusCode, Logger log) {
        return maxAttempts(requestHeaders, statusCode, resolveStatusValue(retriesByStatus::get, statusCode), log);
    }

    /**
     * Returns total permitted attempts when no HTTP response arrives. Static upstreamFailure
     * supplies the default, not the static 502/5xx settings for received responses.
     * Request 502/5xx headers still override it, matching Gateleen's eventual error status.
     *
     * @param requestHeaders headers of the incoming request, may contain {@code x-route-retry-*} overrides
     * @param log            logger for invalid override headers, or {@code null} to suppress the warning
     * @return total permitted attempts including the initial one (always {@code >= 1})
     */
    public long maxAttemptsForUpstreamFailure(MultiMap requestHeaders, Logger log) {
        return maxAttempts(requestHeaders, BAD_GATEWAY.getStatusCode(), retriesByStatus.get(UPSTREAM_FAILURE_PATTERN), log);
    }

    /**
     * Checks whether any effective policy permits retries for this request, after overrides and
     * the ceiling. Invalid headers are reported when the corresponding failure is encountered,
     * not repeatedly while probing all possible statuses here.
     *
     * @param requestHeaders headers of the incoming request, may contain {@code x-route-retry-*} overrides
     * @return {@code true} if at least one retry is possible for some failure or HTTP error status
     */
    boolean permitsRetries(MultiMap requestHeaders) {
        if (maxRetries == 0) {
            return false;
        }
        // Avoid probing every status on the ordinary, default-zero path.
        boolean positiveSetting = retriesByStatus.values().stream().anyMatch(value -> value > 0);
        if (!positiveSetting) {
            for (Map.Entry<String, String> header : requestHeaders) {
                String name = header.getKey().toLowerCase(Locale.ROOT);
                if (name.startsWith(ROUTE_RETRY_HEADER_PREFIX)
                        && name.substring(ROUTE_RETRY_HEADER_PREFIX.length()).matches("[45](?:[0-9]{2}|xx)")) {
                    Integer count = parseRetryCount(header.getValue());
                    if (count != null && count > 0) {
                        positiveSetting = true;
                        break;
                    }
                }
            }
        }
        if (!positiveSetting) {
            return false;
        }
        if (maxAttemptsForUpstreamFailure(requestHeaders, null) > 1) {
            return true;
        }
        for (int status = 400; status < 600; status++) {
            if (maxAttemptsForResponse(requestHeaders, status, null) > 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Combines a valid request header override (which wins, including zero) with the static
     * setting and converts the result into total attempts, capped by the ceiling.
     *
     * @param requestHeaders    headers of the incoming request
     * @param statusCode        status used to select the {@code x-route-retry-*} header (502 for upstream failures)
     * @param configuredRetries static additional retries for this case, or {@code null} if not configured
     * @param log               logger for invalid override headers, or {@code null}
     * @return total permitted attempts including the initial one, between 1 and {@code 1 + maxRetries}
     */
    private long maxAttempts(MultiMap requestHeaders, int statusCode, Integer configuredRetries, Logger log) {
        Integer retries = resolveRouteRetryCount(requestHeaders, statusCode, log);
        if (retries == null) {
            // Request overrides win, including zero; otherwise use the selected startup setting.
            retries = configuredRetries;
        }
        // Widen before adding the initial attempt so Integer.MAX_VALUE retries cannot overflow.
        long attempts = retries != null ? 1L + retries : 1;
        // Convert the ceiling to total attempts too: the initial attempt is always permitted.
        return Math.min(attempts, 1L + maxRetries);
    }

    /**
     * Reads the supplied JSON or the explicit default JSON when the property is absent.
     * Supplied configuration replaces the default rather than merging with it.
     * Malformed JSON disables the policy; invalid entries are
     * logged and skipped so valid entries remain usable.
     *
     * @return unmodifiable map of additional retries keyed by lower-case status ("503"), class
     * ("5xx") or "upstreamfailure"; empty if the JSON is malformed
     */
    static Map<String, Integer> resolveRetriesByStatus() {
        String property = System.getProperty(RETRIES_BY_STATUS_PROPERTY);
        if (property == null) {
            property = DEFAULT_RETRIES_BY_STATUS;
        }
        JsonObject config;
        try {
            config = new JsonObject(property);
        } catch (DecodeException e) {
            LOG.warn("Ignoring invalid {} (must be a JSON object)", RETRIES_BY_STATUS_PROPERTY, e);
            return Collections.emptyMap();
        }
        Map<String, Integer> retries = new HashMap<>();
        for (Map.Entry<String, Object> entry : config) {
            String pattern = entry.getKey().toLowerCase(Locale.ROOT);
            Object value = entry.getValue();
            // JSON configuration requires integral numbers; headers supply text. Both sources
            // use the same count validation, without narrowing potentially overflowing numbers.
            Integer count = value instanceof Integer || value instanceof Long
                    ? parseRetryCount(value.toString()) : null;
            if (!(pattern.equals(UPSTREAM_FAILURE_PATTERN) || pattern.matches("[45](?:[0-9]{2}|xx)")) || count == null) {
                LOG.warn("Ignoring invalid entry {}={} in {} (expected upstreamFailure or an HTTP error status/class and a non-negative integer)",
                        entry.getKey(), value, RETRIES_BY_STATUS_PROPERTY);
                continue;
            }
            retries.put(pattern, count);
        }
        return Collections.unmodifiableMap(retries);
    }

    /**
     * Looks up the entry for the exact status first and only if absent the entry of its class.
     *
     * @param lookup     returns the value for a pattern such as "503" or "5xx", or {@code null}
     * @param statusCode the HTTP status to resolve
     * @param <T>        the value type of the lookup
     * @return the exact-status value, else the class value, else {@code null}
     */
    private static <T> T resolveStatusValue(Function<String, T> lookup, int statusCode) {
        // Select the exact entry before interpreting its value so an invalid explicit header
        // does not unexpectedly fall through to a broader class policy.
        T exact = lookup.apply(Integer.toString(statusCode));
        return exact != null ? exact : lookup.apply((statusCode / 100) + "xx");
    }

    /**
     * Parses a retry count shared by JSON configuration, the ceiling property and request headers.
     *
     * @param value the text to parse (surrounding whitespace is ignored)
     * @return the non-negative count, or {@code null} if negative, non-numeric or overflowing {@code int}
     */
    private static Integer parseRetryCount(String value) {
        try {
            // Counts represent additional retries: zero disables them, while negative values
            // must not imply unlimited retries. Parse before narrowing to reject overflow.
            int retries = Integer.parseInt(value.trim());
            return retries >= 0 ? retries : null;
        } catch (NumberFormatException e) {
            // The source-specific caller logs invalid input and applies its fallback policy.
            return null;
        }
    }

    /**
     * Removes all per-request retry headers, so they only control the hop that received them.
     *
     * @param headers the (upstream) headers to clean, modified in place; matching is case-insensitive
     */
    static void removeRetryHeaders(MultiMap headers) {
        List<String> retryHeaders = new ArrayList<>();
        for (String name : headers.names()) {
            if (name.toLowerCase(Locale.ROOT).startsWith(ROUTE_RETRY_HEADER_PREFIX)) {
                retryHeaders.add(name);
            }
        }
        retryHeaders.forEach(headers::remove);
    }

    /**
     * Resolves a per-request retry count, preferring an exact HTTP status over its status class.
     * An absent or invalid value leaves the static policy in effect.
     *
     * @param headers    headers of the incoming request
     * @param statusCode the HTTP status to resolve ({@code x-route-retry-503} before {@code x-route-retry-5xx})
     * @param log        logger for an invalid header value, or {@code null} to suppress the warning
     * @return the additional retries requested by the header, or {@code null} if absent or invalid
     */
    static Integer resolveRouteRetryCount(MultiMap headers, int statusCode, Logger log) {
        // An exact status (e.g. 404) overrides the class policy, allowing exceptions such as
        // "retry 4xx responses, but never retry 404".
        // Match before parsing: an invalid exact header must not enable the broader class policy.
        String value = resolveStatusValue(pattern -> headers.get(ROUTE_RETRY_HEADER_PREFIX + pattern), statusCode);
        String header = ROUTE_RETRY_HEADER_PREFIX
                + (headers.get(ROUTE_RETRY_HEADER_PREFIX + statusCode) != null ? statusCode : (statusCode / 100) + "xx");
        if (value == null) {
            // Null means "no per-request override"; the caller retains its existing fallback policy.
            return null;
        }
        Integer retries = parseRetryCount(value);
        if (retries != null) {
            return retries;
        }
        // Ignore the invalid override without failing the request, but log it so the client
        // configuration problem is visible. Null lets the caller apply its fallback policy.
        if (log != null) {
            log.warn("Ignoring invalid {}=\"{}\" (must be a non-negative integer)", header, value);
        }
        return null;
    }

}

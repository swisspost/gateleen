package org.swisspush.gateleen.routing;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.codec.http.HttpResponseStatus;
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
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.Pump;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.HeaderFunctions;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.HttpHeaderUtil;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.core.util.StringUtils;
import org.swisspush.gateleen.logging.LogAppenderRepository;
import org.swisspush.gateleen.logging.LoggingHandler;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.logging.LoggingWriteStream;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.routing.auth.AuthHeader;
import org.swisspush.gateleen.routing.auth.AuthStrategy;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.lang.Long.parseLong;
import static org.swisspush.gateleen.core.util.HttpHeaderUtil.removeNonForwardHeaders;
import static org.swisspush.gateleen.core.util.StatusCode.BAD_GATEWAY;
import static org.swisspush.gateleen.core.util.StatusCode.INTERNAL_SERVER_ERROR;
import static org.swisspush.gateleen.core.util.StatusCode.SERVICE_UNAVAILABLE;


/**
 * Forwards requests to the backend.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class Forwarder extends AbstractForwarder {

    public static final String FORWARDED_REQUESTS_METRIC_NAME = "gateleen_forwarded_requests";
    public static final String FORWARDED_RETRIES_METRIC_NAME = "gateleen_forwarded_retries";
    public static final String FORWARDED_FAILURES_METRIC_NAME = "gateleen_forwarded_failures";
    public static final String FORWARDED_REQUESTS_METRIC_DESCRIPTION = "Number of forwarded requests";
    public static final String FORWARDED_RETRIES_METRIC_DESCRIPTION = "Number of additional forwarded request attempts";
    public static final String FORWARDED_FAILURES_METRIC_DESCRIPTION =
            "Number of forwarded requests finally failing with a backend status >= 500 (before status translation) "
                    + "or a Gateleen error status >= 500 (no usable backend response)";
    /** Tag value used when a rule lacks the information for a tag. */
    static final String UNKNOWN_TAG_VALUE = "unknown";
    /** Tag holding the routing rule's URL pattern, e.g. {@code /server/queuing/(.*)}. */
    public static final String FORWARDER_METRIC_TAG_RULE = "rule";
    /**
     * Tag holding the configured target: {@code host:port} (or host wildcard) for URL rules,
     * the target path template for local ({@code path}) rules.
     */
    public static final String FORWARDER_METRIC_TAG_TARGET = "target";

    private final String userProfilePath;
    private final HttpClient client;
    private final Pattern urlPattern;
    /**
     * Static rule target, used for metric tags and {@link #applyHeaderFunctions(Logger, MultiMap)}.
     * Requests resolve host/port wildcards into their own {@link RequestCtx} instead, because
     * concurrent requests (and their retries) must never observe each other's backend.
     */
    private final String target;
    private final Rule rule;
    private final LoggingResourceManager loggingResourceManager;
    private final LogAppenderRepository logAppenderRepository;
    private final MonitoringHandler monitoringHandler;
    private final ResourceStorage storage;
    @Nullable
    private final AuthStrategy authStrategy;
    private final Vertx vertx;

    private static final String ON_BEHALF_OF_HEADER = "x-on-behalf-of";
    private static final String USER_HEADER = "x-rp-usr";
    private static final String USER_HEADER_PREFIX = "x-user-";
    private static final String ETAG_HEADER = "Etag";
    private static final String IF_NONE_MATCH_HEADER = "if-none-match";
    private static final String SELF_REQUEST_HEADER = "x-self-request";
    private static final String HOST_HEADER = "Host";
    private static final int STATUS_CODE_2XX = 2;

    private static final Logger LOG = LoggerFactory.getLogger(Forwarder.class);
    private static AtomicInteger nextErrorId = new AtomicInteger();

    /**
     * Retry settings snapshotted at construction. Local rules never retry themselves: they only
     * re-enter this Gateleen instance, where the rule handling the rewritten path applies its own
     * policy. Retrying on both hops would multiply the attempts (e.g. 3 x 3 = 9 per request).
     */
    private final ForwarderRetryPolicy retryPolicy;

    /**
     * Minimum time (in milliseconds) that must still be left of the request-wide deadline (see
     * {@link #handleRequest}) for a retry to be worth starting. Retrying with only a tiny sliver of
     * budget left would arm a near-zero pool-wait / idle timeout that is almost guaranteed to expire
     * immediately, burning a forwarding attempt for no useful work (and possibly flipping the client
     * status from 502 to 504). If less than this remains we stop retrying and let the current failure
     * be reported instead.
     */
    private static final long MIN_RETRY_BUDGET_MS = 50L;

    private Timer forwardTimer;
    /** Outcome counters, {@code null} without registry or after {@link #shutdown()}. */
    private volatile ForwarderOutcomeCounters outcomeCounters;
    private MeterRegistry meterRegistry;

    /**
     * Controls path suffix handling for hook-based forwarding only.
     * <p>
     * When {@code false} (default): the path suffix after the hook registration path is appended to the destination.
     * When {@code true}: the request is forwarded to the exact destination without any path suffix.
     * <p>
     * This property is only relevant for route hooks and should be set via {@link #setFullUrl(boolean)}.
     * For regular routing rules, this property has no effect as path rewriting is controlled by regex patterns.
     *
     * @see <a href="https://github.com/swisspost/gateleen/issues/758">GitHub issue #758</a>
     */
    private boolean fullUrl = false;

    /** @deprecated use {@link #newForwarder()}. */
    public Forwarder(Vertx vertx, HttpClient client, Rule rule, final ResourceStorage storage,
                     LoggingResourceManager loggingResourceManager, LogAppenderRepository logAppenderRepository,
                     @Nullable MonitoringHandler monitoringHandler, String userProfilePath, @Nullable AuthStrategy authStrategy) {
        this(vertx, client, rule, storage, loggingResourceManager, logAppenderRepository,
                monitoringHandler, userProfilePath, authStrategy, null);
    }

    /**
     * Likely you want to use {@link #newForwarder()} instead.
     */
    public Forwarder(
            Vertx vertx,
            HttpClient client,
            Rule rule,
            final ResourceStorage storage,
            LoggingResourceManager loggingResourceManager,
            LogAppenderRepository logAppenderRepository,
            MonitoringHandler monitoringHandler,
            String userProfilePath,
            AuthStrategy authStrategy,
            MeterRegistry meterRegistry
    ) {
        super(rule, loggingResourceManager, logAppenderRepository, monitoringHandler);
        this.vertx = vertx;
        this.client = client;
        this.rule = rule;
        this.retryPolicy = isLocalRule()
                ? ForwarderRetryPolicy.disabled()
                : ForwarderRetryPolicy.fromSystemProperties();
        this.loggingResourceManager = loggingResourceManager;
        this.logAppenderRepository = logAppenderRepository;
        this.monitoringHandler = monitoringHandler;
        this.storage = storage;
        this.urlPattern = Pattern.compile(rule.getUrlPattern());
        this.target = rule.getHost() + ":" + rule.getPort();
        this.userProfilePath = userProfilePath;
        this.authStrategy = authStrategy;
        setMeterRegistry(meterRegistry);
    }

    /**
     * Configure and instantiate a {@link Forwarder} more conveniently.
     */
    public static ForwarderBuilder newForwarder() {
        return new ForwarderBuilder();
    }

    /**
     * Builds the target URI for forwarding a request, using default path rewriting behavior.
     * <p>
     * This is a convenience overload that delegates to {@link #buildTargetUri(Pattern, String, String, boolean)}
     * with {@code fullUrl=false}.
     *
     * @param urlPattern the compiled regex pattern from the routing rule's URL pattern
     * @param requestUri the incoming request URI to be transformed
     * @param rulePath   the destination path template (may contain regex back-references like {@code $1})
     * @return the transformed target URI with any duplicate slashes normalized to single slashes
     * @see #buildTargetUri(Pattern, String, String, boolean)
     */
    static String buildTargetUri(Pattern urlPattern, String requestUri, String rulePath) {
        return buildTargetUri(urlPattern, requestUri, rulePath, false);
    }

    /**
     * Builds the target URI for forwarding a request.
     * <p>
     * The behavior depends on the {@code fullUrl} flag:
     * <ul>
     *   <li><b>{@code fullUrl=false}</b> (default): Uses regex replacement where the {@code urlPattern}
     *       is matched against {@code requestUri} and replaced with {@code rulePath}. This allows
     *       capturing groups in the pattern to be referenced in the path (e.g., {@code $1}, {@code $2}).</li>
     *   <li><b>{@code fullUrl=true}</b>: Returns {@code rulePath} directly without any pattern matching.
     *       This is used for hook-based forwarding where the exact destination should be used.</li>
     * </ul>
     * In both cases, any duplicate slashes ({@code //}) in the result are normalized to single slashes.
     *
     * @param urlPattern the compiled regex pattern from the routing rule's URL pattern
     * @param requestUri the incoming request URI to be transformed
     * @param rulePath   the destination path template (may contain regex back-references when {@code fullUrl=false})
     * @param fullUrl    when {@code true}, bypasses pattern matching and uses {@code rulePath} directly;
     *                   when {@code false}, performs regex replacement
     * @return the transformed target URI with any duplicate slashes normalized to single slashes
     * @see #fullUrl
     */
    static String buildTargetUri(Pattern urlPattern, String requestUri, String rulePath, boolean fullUrl) {
        if (fullUrl) {
            return rulePath.replaceAll("\\/\\/", "/");
        }
        return urlPattern.matcher(requestUri).replaceFirst(rulePath).replaceAll("\\/\\/", "/");
    }

    /**
     * Sets whether the forwarder should use the full destination URL without appending any path suffix.
     * This is only relevant for hook-based forwarding.
     *
     * @param fullUrl when {@code true}, forwards to exact destination; when {@code false}, appends path suffix
     * @see #fullUrl
     */
    public void setFullUrl(boolean fullUrl) {
        this.fullUrl = fullUrl;
    }

    /**
     * Sets the MeterRegistry for this Forwarder.
     * If the provided MeterRegistry is not null, it initializes the forward timer and acquires the
     * outcome counters ({@link ForwarderOutcomeCounters}) with the appropriate metric names,
     * descriptions and tags. Outcome counters acquired by a previous call are released first.
     *
     * @param meterRegistry the MeterRegistry to set, or {@code null} to disable the metrics
     *
     * @deprecated Use Forwarder.{@link Forwarder#newForwarder()}.withMeterRegistry(...).
     */
    @Override
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        ForwarderOutcomeCounters previous = outcomeCounters;
        if (meterRegistry != null) {
            forwardTimer = Timer.builder(FORWARDS_METRIC_NAME)
                    .description(FORWARDS_METRIC_DESCRIPTION)
                    .publishPercentiles(0.75, 0.95)
                    .tag(FORWARDER_METRIC_TAG_METRICNAME, metricNameTag)
                    .tag(FORWARDER_METRIC_TAG_TYPE, getRequestTarget(target))
                    .register(meterRegistry);
            // Acquire before releasing, so re-setting the same registry never drops the meters.
            outcomeCounters = ForwarderOutcomeCounters.acquire(meterRegistry, outcomeTags());
        } else {
            outcomeCounters = null;
        }
        if (previous != null) {
            previous.release();
        }
    }

    /**
     * Builds the tags of the outcome counters. They are bounded by the number of existing
     * forwarders (routing rules and hook routes), never by request URLs.
     *
     * @return the {@code metricName}, {@code rule} and {@code target} tags
     */
    private Tags outcomeTags() {
        return Tags.of(
                FORWARDER_METRIC_TAG_METRICNAME, metricNameTag,
                FORWARDER_METRIC_TAG_RULE, Objects.toString(rule.getUrlPattern(), UNKNOWN_TAG_VALUE),
                FORWARDER_METRIC_TAG_TARGET, configuredTarget());
    }

    /**
     * Tells whether this forwarder handles a local rule ({@code path} routing rule or hook route
     * with a path destination), which re-enters this Gateleen instance through the self client
     * instead of calling a backend. Local rules never retry and pass the retry headers on.
     *
     * @return {@code true} if the rule's scheme is {@code local}
     */
    private boolean isLocalRule() {
        return "local".equals(rule.getScheme());
    }

    /**
     * Describes the configured (not the per-request resolved) target of the rule for the
     * {@code target} tag.
     *
     * @return the target path template for local rules, otherwise {@code host:port} where host and
     * port may be the configured wildcards; missing parts are {@value #UNKNOWN_TAG_VALUE}
     */
    private String configuredTarget() {
        if (isLocalRule()) {
            return Objects.toString(rule.getPath(), UNKNOWN_TAG_VALUE);
        }
        String host = rule.getHost() != null ? rule.getHost() : rule.getHostWildcard();
        String port = rule.getPortWildcard() != null ? rule.getPortWildcard() : String.valueOf(rule.getPort());
        return Objects.toString(host, UNKNOWN_TAG_VALUE) + ":" + port;
    }

    /**
     * Stops publishing metrics for this forwarder: drops the timer reference and releases the
     * outcome counters, which removes them from the registry unless another forwarder with the
     * same tags still uses them. Requests still in flight are no longer counted.
     */
    public void shutdown() {
        forwardTimer = null; // remove this reference from Forwarder
        ForwarderOutcomeCounters counters = outcomeCounters;
        outcomeCounters = null;
        if (counters != null) {
            counters.release();
        }
    }

    private Map<String, String> createProfileHeaderValues(JsonObject profile, Logger log) {
        Map<String, String> profileValues = new HashMap<>();
        if (rule.getProfile() != null) {
            String[] ruleProfile = rule.getProfile();
            for (String headerKey : ruleProfile) {
                String headerValue = profile.getString(headerKey);
                if (headerKey != null && headerValue != null) {
                    profileValues.put(USER_HEADER_PREFIX + headerKey, headerValue);
                    log.debug("Sending header-information for key {}, value = {}", headerKey, headerValue);
                } else {
                    if (headerKey != null) {
                        log.debug("We should send profile information '{}' but this information was not found in profile.", headerKey);
                    } else {
                        log.debug("We should send profile information but header key is null.");
                    }
                }
            }
        } else {
            log.debug("rule.profile is null, this rule will not send profile information.");
        }
        return profileValues;
    }

    @Override
    public void handle(final RoutingContext ctx) {
        handle(ctx, null, null);
    }

    /**
     * Allows to handle a request which was already consumed.
     * If the parameter <code>bodyData</code> is not null, the
     * request was consumed, and you can't read the body of the
     * request again.
     *
     * <p>
     * If {@code bodyData} is {@code null}, the method is idempotent, the effective retry policy
     * (static configuration and {@code x-route-retry-*} headers, capped by the ceiling) permits at
     * least one retry and the declared body size is within
     * {@link ForwarderRetryPolicy#MAX_RETRY_BODY_BYTES_PROPERTY}, the body is buffered first so it
     * can be replayed; otherwise it is streamed (and therefore not retried).
     *
     * @param ctx          - the original request context
     * @param bodyData     - a buffer with the body data, null if the request
     *                     was not yet consumed
     * @param afterHandler - optional handler called after a successful (2xx) backend response was
     *                     forwarded completely; may be {@code null}
     */
    public void handle(final RoutingContext ctx, final Buffer bodyData, @Nullable final Handler<Void> afterHandler) {
        HttpServerRequest req = ctx.request();
        final Logger log = RequestLoggerFactory.getLogger(Forwarder.class, req);

        if (rule.hasHeadersFilterPattern() && !doHeadersFilterMatch(ctx.request())) {
            ctx.next();
            return;
        }
        // Only a buffered body can be replayed. Buffer it solely when a retry is actually
        // possible and the body is small enough; all other requests keep streaming their body.
        if (bodyData == null && isIdempotent(req.method()) && retryPolicy.permitsRetries(req.headers())) {
            if (mayBufferBody(req)) {
                bufferBodyAndForward(ctx, afterHandler, log);
                return;
            }
            log.debug("Body of {} {} is too large or of unknown length to be buffered; streaming it without retries",
                    req.method(), req.uri());
        }
        forward(ctx, bodyData, afterHandler, log);
    }

    /**
     * Decides from the declared length whether the body of a not yet consumed request may be
     * buffered for replay (see {@link ForwarderRetryPolicy#MAX_RETRY_BODY_BYTES_PROPERTY}). An
     * already ended request and an HTTP/1.x request without {@code Content-Length} and
     * {@code Transfer-Encoding} have no body. Bodies of unknown length (chunked, or HTTP/2 without
     * {@code Content-Length}) and invalid lengths are never buffered.
     *
     * @param req the incoming request
     * @return {@code true} if the body may be buffered in memory
     */
    private boolean mayBufferBody(HttpServerRequest req) {
        if (req.isEnded()) {
            return true;
        }
        MultiMap headers = req.headers();
        if (headers.contains(HttpHeaders.TRANSFER_ENCODING)) {
            return false;
        }
        String contentLength = headers.get(HttpHeaders.CONTENT_LENGTH);
        if (contentLength == null) {
            return req.version() != HttpVersion.HTTP_2;
        }
        try {
            return retryPolicy.mayBufferBody(Long.parseLong(contentLength.trim()));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Reads the complete body of a not yet consumed request into memory and then forwards it via
     * {@link #forward}, so that the buffered body can be replayed on retries. An already ended
     * request is forwarded with an empty buffer. If the body cannot be read, the client receives
     * {@code 400 Bad Request} and nothing is forwarded.
     *
     * @param ctx          the routing context of the incoming request
     * @param afterHandler optional handler called after a successful (2xx) forwarded response; may be {@code null}
     * @param log          the request-scoped logger
     */
    private void bufferBodyAndForward(final RoutingContext ctx, @Nullable final Handler<Void> afterHandler, final Logger log) {
        HttpServerRequest req = ctx.request();
        if (req.isEnded()) {
            // Nothing left to read (e.g. a bodyless GET); the streaming path would send no body either.
            forward(ctx, Buffer.buffer(), afterHandler, log);
            return;
        }
        req.body().onComplete(event -> {
            if (event.failed()) {
                log.warn("Failed to read request body of {} for retryable forwarding: {}", req.uri(), event.cause().getMessage());
                respondError(req, StatusCode.BAD_REQUEST);
                return;
            }
            forward(ctx, event.result(), afterHandler, log);
        });
        // Earlier handlers may have paused the request; the body can only be collected while it flows.
        req.resume();
    }

    /**
     * Resolves the upstream target of this request (host/port wildcards evaluated once, so all
     * retries of this request use the same backend), authenticates, loads the optional profile
     * headers and then hands the request over to {@link #handleRequest}. Wildcard resolution
     * failures are answered with an error response directly.
     *
     * @param ctx          the routing context of the incoming request
     * @param bodyData     the buffered request body, or {@code null} to stream the body of the request
     * @param afterHandler optional handler called after a successful (2xx) forwarded response; may be {@code null}
     * @param log          the request-scoped logger
     */
    private void forward(final RoutingContext ctx, final Buffer bodyData, @Nullable final Handler<Void> afterHandler, final Logger log) {
        HttpServerRequest req = ctx.request();
        String host = null;
        if (rule.hasHostWildcard()) {
            try {
                host = urlPattern.matcher(req.uri()).replaceFirst(rule.getHostWildcard());
                log.debug("Dynamic host for wildcard {} is {}", rule.getHostWildcard(), host);
                rule.setHost(host);
            } catch (NumberFormatException ex) {
                log.error("Could not extract string value from wildcard {}. Got {}", rule.getHostWildcard(), host);
                respondError(req, StatusCode.INTERNAL_SERVER_ERROR);
                return;
            } catch (IndexOutOfBoundsException ex) {
                log.error("No group could be found for wildcard {}", rule.getHostWildcard());
                respondError(req, StatusCode.INTERNAL_SERVER_ERROR);
                return;
            }
        } else {
            host = rule.getHost();
        }

        int port;
        if (rule.hasPortWildcard()) {
            String dynamicPortStr = null;
            try {
                dynamicPortStr = urlPattern.matcher(req.uri()).replaceFirst(rule.getPortWildcard());
                log.debug("Dynamic port for wildcard {} is {}", rule.getPortWildcard(), dynamicPortStr);
                port = Integer.parseInt(dynamicPortStr);
            } catch (NumberFormatException ex) {
                log.error("Could not extract a numeric value from wildcard {}. Got {}", rule.getPortWildcard(), dynamicPortStr);
                respondError(req, StatusCode.INTERNAL_SERVER_ERROR);
                return;
            } catch (IndexOutOfBoundsException ex) {
                log.error("No group could be found for wildcard {}", rule.getPortWildcard());
                respondError(req, StatusCode.INTERNAL_SERVER_ERROR);
                return;
            }
        } else {
            port = rule.getPort();
        }
        final UpstreamTarget upstream = new UpstreamTarget(host, port);
        final String target = upstream.target;

        if (monitoringHandler != null) {
            monitoringHandler.updateRequestsMeter(target, req.uri());
            monitoringHandler.updateRequestPerRuleMonitoring(req, rule.getMetricName());
        }
        final String targetUri = buildTargetUri(urlPattern, req.uri(), rule.getPath(), fullUrl);
        log.debug("Forwarding request: {} to {}://{} with rule {}", req.uri(), rule.getScheme(), target + targetUri, rule.getRuleIdentifier());
        final String userId = extractUserId(req, log);
        req.pause(); // pause the request to avoid problems with starting another async request (storage)

        maybeAuthenticate(rule).onComplete(event -> {
            if (event.failed()) {
                log.error("Failed to authenticate request. Cause: {}", event.cause().getMessage());
                respondError(req, StatusCode.UNAUTHORIZED);
                req.resume();
                return;
            }
            Optional<AuthHeader> authHeader = event.result();
            if (userId != null && rule.getProfile() != null && userProfilePath != null) {
                log.debug("Get profile information for user '{}' to append to headers", userId);
                String userProfileKey = String.format(userProfilePath, userId);
                storage.get(userProfileKey, buffer -> {
                    Map<String, String> profileHeaderMap = new HashMap<>();
                    if (buffer != null) {
                        JsonObject profile = new JsonObject(buffer.toString());
                        profileHeaderMap = createProfileHeaderValues(profile, log);
                        log.debug("Got profile information of user '{}'", userId);
                        log.debug("Going to send parts of the profile in header: {}", profileHeaderMap);
                    } else {
                        log.debug("No profile information found in local storage for user '{}'", userId);
                    }
                    handleRequest(req, bodyData, upstream, targetUri, log, profileHeaderMap, authHeader, afterHandler);
                });
            } else {
                handleRequest(req, bodyData, upstream, targetUri, log, null, authHeader, afterHandler);
            }
        });
    }

    private void handleForwardDurationMetrics(Timer.Sample timerSample) {
        if (timerSample != null && forwardTimer != null) {
            timerSample.stop(forwardTimer);
        }
    }

    /**
     * Stops the request metrics ({@link MonitoringHandler}, the {@code gateleen_forwarded_seconds}
     * timer and the outcome counters) exactly once per request, no matter how many attempts or
     * terminal paths call it.
     *
     * @param ctx         the per-request context holding the metric start values
     * @param finalStatus status known at completion: the backend status (before translation) once
     *                    a usable backend response was accepted, otherwise the error status Gateleen
     *                    answers with; {@code >= 500} counts as a failure
     */
    private void completeRequestMetrics(RequestCtx ctx, int finalStatus) {
        if (ctx.metricsCompleted.compareAndSet(false, true)) {
            ForwarderOutcomeCounters counters = outcomeCounters;
            if (counters != null) {
                counters.record(ctx.attempt - 1, finalStatus >= 500);
            }
            if (monitoringHandler != null) {
                monitoringHandler.stopRequestMetricTracking(rule.getMetricName(), ctx.startTime, ctx.dnReq.uri());
            }
            handleForwardDurationMetrics(ctx.timerSample);
        }
    }

    /**
     * Marks the request as finished exactly once: cancels the request-wide deadline and pool-wait
     * timers and completes the request metrics. Every terminal path must call this <em>before</em>
     * writing the response, and only the caller that gets {@code true} may write it.
     *
     * @param ctx         the per-request context
     * @param finalStatus status used for the outcome metrics if not yet completed
     * @return {@code true} if this call finished the request, {@code false} if it was already finished
     */
    private boolean finishRequest(RequestCtx ctx, int finalStatus) {
        if (!ctx.finished.compareAndSet(false, true)) {
            return false;
        }
        vertx.cancelTimer(ctx.deadlineTimerId);
        vertx.cancelTimer(ctx.poolWaitTimerId);
        completeRequestMetrics(ctx, finalStatus);
        return true;
    }

    /**
     * Like {@link #finishRequest(RequestCtx, int)}, and additionally logs the final outcome if the
     * request was retried (see {@link #logRetryOutcome}).
     *
     * @param ctx     the per-request context
     * @param status  HTTP status of the final outcome (upstream status if a response was received).
     * @param failure reason why the request failed, or {@code null} if a response was delivered completely.
     * @return {@code true} if this call finished the request, {@code false} if it was already finished
     */
    private boolean finishRequest(RequestCtx ctx, int status, @Nullable String failure) {
        if (!finishRequest(ctx, status)) {
            return false;
        }
        logRetryOutcome(ctx, status, failure);
        return true;
    }

    /**
     * Logs a summary for a request that was retried at least once: INFO if the final attempt
     * delivered a non-error response ({@code < 400}), ERROR otherwise. Requests without retries
     * are not logged here; their existing logging is unchanged.
     *
     * @param ctx     the per-request context (provides method, target URL and attempt count)
     * @param status  HTTP status of the final outcome
     * @param failure reason why the request failed, or {@code null} if a response was delivered completely
     * @return {@code true} if a summary was logged.
     */
    private boolean logRetryOutcome(RequestCtx ctx, int status, @Nullable String failure) {
        long retries = ctx.attempt - 1;
        if (retries <= 0) {
            return false;
        }
        String url = rule.getScheme() + "://" + ctx.upstream.target + ctx.targetUri;
        if (failure == null && status < 400) {
            ctx.log.info("Request {} {} succeeded after {} retries with HTTP {}{}",
                    ctx.dnReq.method(), url, retries, status, clientStatusNote(ctx, status, failure));
        } else {
            ctx.log.error("Request {} {} failed after {} retries with HTTP {}{}{}",
                    ctx.dnReq.method(), url, retries, status, clientStatusNote(ctx, status, failure),
                    failure == null ? "" : ": " + failure);
        }
        return true;
    }

    /**
     * Builds a note for the retry summary if the client receives a different status than the
     * backend's final one (e.g. because of a status code translation).
     *
     * @param ctx     the per-request context
     * @param status  the backend status reported in the summary
     * @param failure the failure reason, or {@code null} if the response was delivered completely
     * @return {@code " (client received HTTP <code>)"} or an empty string.
     */
    private String clientStatusNote(RequestCtx ctx, int status, @Nullable String failure) {
        if (failure != null || ctx.dnRsp == null || ctx.dnRsp.getStatusCode() == status) {
            return "";
        }
        return " (client received HTTP " + ctx.dnRsp.getStatusCode() + ")";
    }

    /**
     * Called when the request-wide deadline of a retry-enabled request expires, or when too little
     * of it is left for another attempt. Finishes the request (if not already finished), logs the
     * outcome and answers with {@code 504} if no upstream request was obtained yet, {@code 502}
     * otherwise; a partially streamed response is closed instead (and the received response is
     * logged, as the upstream reset prevents its regular end handling). The current upstream
     * request is reset.
     *
     * @param ctx    the per-request context
     * @param reason the reason used in the log entries
     */
    private void expireRequest(RequestCtx ctx, String reason) {
        HttpServerResponse response = ctx.dnReq.response();
        boolean responseStarted = isResponseStarted(ctx);
        int status = responseStarted ? response.getStatusCode()
                : (ctx.upReq == null ? StatusCode.TIMEOUT : BAD_GATEWAY).getStatusCode();
        if (!finishRequest(ctx, status)) {
            return;
        }
        ctx.log.warn("{} for {}", reason, ctx.targetUri);
        if (!logRetryOutcome(ctx, status, reason)) {
            error(reason, ctx);
        }
        if (ctx.upRes != null) {
            logResponseOnce(ctx);
        }
        if (responseStarted) {
            // A partially streamed response cannot be replaced with an HTTP error.
            response.close();
        } else {
            respondError(ctx.dnReq, ctx.upReq == null ? StatusCode.TIMEOUT : BAD_GATEWAY);
        }
        if (ctx.upReq != null) {
            ctx.upReq.reset(0);
        }
        ctx.dnReq.resume();
    }

    /**
     * Returns the userId defined in the on-behalf-of-header if provided, the userId from user-header otherwise.
     *
     * @param request request
     * @param log     log
     */
    private String extractUserId(HttpServerRequest request, Logger log) {
        String onBehalfOf = StringUtils.getStringOrEmpty(request.headers().get(ON_BEHALF_OF_HEADER));
        if (StringUtils.isNotEmpty(onBehalfOf)) {
            log.debug("Using values from x-on-behalf-of header instead of taking them from x-rp-usr header");
            return onBehalfOf;
        } else {
            return request.headers().get(USER_HEADER);
        }
    }

    /**
     * Execute the HeaderFunctions chain and apply the configured rules headers therefore.
     * Uses the target configured in the rule at construction time.
     *
     * @param log     The logger to be used
     * @param headers The headers which must be updated according the current forwarders rule
     * @return null if everything is properly done or a error message if something went wrong.
     */
    String applyHeaderFunctions(final Logger log, MultiMap headers) {
        return applyHeaderFunctions(log, headers, target);
    }

    /**
     * Executes the HeaderFunctions chain of the rule and forces the {@code Host} header to the
     * given target unless the rule itself changed it.
     *
     * @param log     the logger to be used
     * @param headers the headers to update according to the rule
     * @param target  the upstream {@code host:port} resolved for this request, used as default Host header
     * @return {@code null} if everything was applied, otherwise the error message of the header functions
     */
    private String applyHeaderFunctions(final Logger log, MultiMap headers, String target) {
        final String hostHeaderBefore = HttpHeaderUtil.getHeaderValue(headers, HOST_HEADER);
        final HeaderFunctions.EvalScope evalScope = rule.getHeaderFunction().apply(headers);
        final String hostHeaderAfter = HttpHeaderUtil.getHeaderValue(headers, HOST_HEADER);
        // see https://github.com/swisspush/gateleen/issues/394
        if (hostHeaderAfter == null || hostHeaderAfter.equals(hostHeaderBefore)) {
            // there was no host header before or the host header was not updated by the rule given,
            // therefore it will be forced overwritten independent of the incoming value if necessary.
            final String newHost = target.split("/")[0];
            if (newHost != null && !newHost.isEmpty() && !newHost.equals(hostHeaderAfter)) {
                headers.set(HOST_HEADER, newHost);
                log.debug("Host header {} replaced by default target value: {}",
                        hostHeaderBefore,
                        newHost);
            }
        } else {
            // the host header was changed by the configured routing and therefore
            // it is not updated. This allows us to configure for certain routings to external
            // url a dedicated Host header which will not be overwritten.
            log.debug("Host header {} replaced by rule value: {}",
                    hostHeaderBefore,
                    hostHeaderAfter);
        }
        return evalScope.getErrorMessage();
    }

    /**
     * Builds the {@link RequestCtx} for a request (logging handler, metrics, timeout), arms the
     * request-wide deadline when the request is retry-enabled (buffered body, idempotent method,
     * effective policy permits retries and a positive timeout) and starts the first attempt via
     * {@link #sendToTarget}.
     *
     * @param req              the incoming request
     * @param bodyData         the buffered request body, or {@code null} if the body is streamed
     * @param upstream         the upstream target resolved once for this request
     * @param targetUri        the URI to request on the upstream target
     * @param log              the request-scoped logger
     * @param profileHeaderMap profile values to send as headers, may be {@code null}
     * @param authHeader       optional authentication header for the upstream request
     * @param afterHandler     optional handler called after a successful (2xx) forwarded response; may be {@code null}
     */
    private void handleRequest(final HttpServerRequest req, final Buffer bodyData, final UpstreamTarget upstream, final String targetUri,
                               final Logger log, final Map<String, String> profileHeaderMap,
                               Optional<AuthHeader> authHeader, @Nullable final Handler<Void> afterHandler
    ) {
        /* collect stuff we need */
        final LoggingHandler loggingHandler = new LoggingHandler(loggingResourceManager, logAppenderRepository, req, vertx.eventBus());
        // Capture the original payload once; the same logging handler survives all retry attempts.
        if (bodyData != null) {
            loggingHandler.appendRequestPayload(bodyData);
        }
        String timeout = req.headers().get("x-timeout");
        String uniqueId = req.headers().get("x-rp-unique_id");
        Timer.Sample timerSample = (meterRegistry == null) ? null : Timer.start(meterRegistry);
        Long startTime = (monitoringHandler == null) ? null
                : monitoringHandler.startRequestMetricTracking(rule.getMetricName(), req.uri());
        final long timeoutMs = timeout != null ? parseLong(timeout) : rule.getTimeout();
        /* bundle it into a handy context */
        RequestCtx ctx = new RequestCtx(
                req, log, upstream, targetUri, startTime, timerSample, profileHeaderMap, loggingHandler,
                afterHandler, timeout, timeoutMs, uniqueId, authHeader.orElse(null), bodyData);

        // Preserve per-phase pool/idle timeouts for requests that cannot retry. Replayable requests
        // with an effective retry policy instead share a hard deadline, including response bodies.
        if (timeoutMs > 0 && bodyData != null && isIdempotent(req.method())
                && retryPolicy.permitsRetries(req.headers())) {
            ctx.deadlineNanos = System.nanoTime() + MILLISECONDS.toNanos(timeoutMs);
            ctx.deadlineTimerId = vertx.setTimer(timeoutMs, id -> expireRequest(ctx, "Request-wide retry timeout"));
        }

        sendToTarget(ctx);
    }

    /**
     * Initiates (or, on a retry, re-initiates) the request to the upstream target described by
     * {@code ctx}. This includes arming the pool-wait guard timer and calling {@code client.request()}.
     * <p>
     * <b>Why this is a separate, re-callable method.</b> The forwarding flow used to inline this block
     * inside {@link #handleRequest}. It was extracted so that the exact same "open a connection and
     * fire the request" sequence can be executed again for a retry, without having to rebuild the
     * {@link RequestCtx}, re-parse headers, restart metric tracking or re-run authentication. Each
     * invocation starts a <em>fresh</em> upstream attempt: a new pool-wait guard timer, a new
     * {@code client.request(...)} call and (later, in {@link #onNewRequestComplete}) a brand-new
     * {@link HttpClientRequest} assigned to {@code ctx.upReq}.
     * <p>
     * <b>State that is intentionally shared across attempts</b> (because it lives on {@code ctx}):
     * the downstream request/response, the resolved upstream host/port, the buffered
     * {@code bodyData} that gets replayed, the logging handler, the metric sample and the
     * {@code attempt} counter. Per-attempt state (the {@code responded} guard, the upstream
     * request object and {@code ctx.poolWaitTimerId}) is created or reassigned here, and callbacks
     * of superseded attempts are ignored, so two attempts never interfere with each other.
     * <p>
     * <b>Important:</b> a retry is only ever triggered from a state where the downstream response has
     * not been written yet (guaranteed by {@link #mayRetry}), so re-entering this method cannot
     * corrupt a partially delivered client response.
     *
     * @param ctx the per-request context; the same instance is reused across retries.
     */
    private void sendToTarget(RequestCtx ctx) {
        if (ctx.finished.get()) {
            return;
        }
        ctx.upReq = null;
        /*
         * Arm a pool-wait guard timer BEFORE calling client.request().
         * If the connection pool is exhausted, client.request() will queue this request
         * internally and never invoke the callback until a slot is freed. Without this
         * guard, queued requests have no timeout of their own and can hang indefinitely.
         *
         * The timer fires after remainingTimeoutMs(ctx): the time left until the request-wide
         * deadline for retry-enabled requests, otherwise the full per-phase timeout. If it fires
         * first it finishes the request with 504 and abandons the pool-queue entry. If
         * client.request() fires first it cancels the timer and proceeds normally.
         *
         * When timeoutMs <= 0 the timer is skipped (no timeout configured).
         *
         * NOTE ON RETRIES: `responded` is local, so every call to sendToTarget() gets its own
         * guard. ctx.poolWaitTimerId is overwritten per attempt; the timer of a previous (failed)
         * attempt has already fired or been cancelled by then, and finishRequest() cancels the
         * current one.
         */
        final AtomicBoolean responded = new AtomicBoolean(false);
        // Retry-enabled requests spend their shared budget here; other requests retain a full
        // pool-wait timeout followed by a separate upstream idle timeout.
        final long attemptTimeoutMs = remainingTimeoutMs(ctx);
        final long poolWaitTimerId = ctx.timeoutMs > 0
                ? vertx.setTimer(attemptTimeoutMs, id -> {
                    if (responded.compareAndSet(false, true) && finishRequest(ctx, StatusCode.TIMEOUT.getStatusCode())) {
                        ctx.log.warn("Timeout waiting for pool connection to {}{}", ctx.upstream.target, ctx.targetUri);
                        if (!logRetryOutcome(ctx, StatusCode.TIMEOUT.getStatusCode(), "Timeout waiting for connection pool")) {
                            error("Timeout waiting for connection pool", ctx);
                        }
                        respondError(ctx.dnReq, StatusCode.TIMEOUT);
                    }
                })
                : -1;
        ctx.poolWaitTimerId = poolWaitTimerId;

        /* initiate request to target server */
        client.request(ctx.dnReq.method(), ctx.upstream.port, ctx.upstream.host, ctx.targetUri, ev -> {
            if (ctx.finished.get() || !responded.compareAndSet(false, true)) {
                /* The request already finished (pool-wait 504, request-wide deadline or another
                 * terminal path) — abandon the connection we just obtained so it is returned
                 * cleanly to the pool */
                if (ev.succeeded()) ev.result().reset(0);
                return;
            }
            vertx.cancelTimer(poolWaitTimerId);
            if (ev.failed()) {
                ctx.log.warn("Problem to request {}: {}", ctx.targetUri, ev.cause());
                // No connection was established (refused, DNS, connect timeout), so the backend
                // cannot have received the request: treat it as an upstream failure.
                if (mayRetry(ctx, retryPolicy.maxAttemptsForUpstreamFailure(ctx.dnReq.headers(), ctx.log))) {
                    ctx.attempt++;
                    ctx.log.info("Retrying request (attempt {}) to {}://{}{} after connection failure",
                            ctx.attempt, rule.getScheme(), ctx.upstream.target, ctx.targetUri);
                    sendToTarget(ctx);
                    return;
                }
                finishRequest(ctx, SERVICE_UNAVAILABLE.getStatusCode(), "Problem to request: " + ev.cause());
                tryRespondWithServiceUnavailable(ctx.dnReq.response(), ctx.log, "findme_48hj349lgnt8j");
                ctx.dnReq.resume();
                return;
            }
            onNewRequestCompleteNoThrow(ev, ctx);
        });
    }

    /**
     * Decides whether an HTTP error response or upstream response failure may be retried.
     * <p>
     * Retrying an HTTP request that failed while we were waiting for the response is delicate,
     * because the target server may already have received and (partially or fully) processed the
     * request before the connection broke. We therefore only retry when <b>all</b> of the
     * following conditions are satisfied; otherwise the caller returns the backend response or
     * reports the upstream failure as {@code 502 Bad Gateway}:
     * <ol>
     *   <li><b>Attempt budget left</b> (request headers override the static status policy, which
     *       is capped by the server-controlled retry ceiling). {@code attempt} is 1-based and counts
     *       the tries already started. Without a matching policy the default budget of {@code 1}
     *       disables retrying, and HTTP error responses are not retried.</li>
     *   <li><b>Buffered body</b> ({@code ctx.bodyData != null}). When the body was streamed straight
     *       from the incoming request via a {@code Pump}, that stream has already been consumed and
     *       <em>cannot</em> be read a second time. Only a body we hold fully in memory can be replayed
     *       onto a fresh upstream connection. {@link #handle(RoutingContext, Buffer, Handler)}
     *       buffers the body itself when the request is idempotent and a retry policy applies.</li>
     *   <li><b>Idempotent method</b> ({@link #isIdempotent(HttpMethod)}). Replaying e.g. a
     *       {@code POST} could cause the target to perform the same side effect twice. Idempotent
     *       methods are, by definition, safe to send more than once.</li>
     *   <li><b>Downstream response untouched</b> ({@link #isResponseStarted}). If we had already begun
     *       streaming a response back to the calling client we could no longer cleanly "start over";
     *       at the point this is evaluated (the upstream <em>response</em> failed) nothing has been
     *       written downstream yet, and this check defends against any future code change that might
     *       violate that assumption.</li>
     * </ol>
     * Additionally, the request must not be finished yet (e.g. by the request-wide deadline or a
     * downstream disconnect), and the request-wide deadline must still leave at least
     * {@link #MIN_RETRY_BUDGET_MS} for another attempt.
     *
     * @param ctx the per-request context (carries the attempt counter and the buffered body).
     * @param maxAttempts total attempts permitted by the retry policy, including the initial attempt.
     * @return {@code true} if another forwarding attempt may safely be started.
     */
    private boolean mayRetry(RequestCtx ctx, long maxAttempts) {
        return ctx.attempt < maxAttempts
                && !ctx.finished.get()
                && ctx.bodyData != null
                && isIdempotent(ctx.dnReq.method())
                && !isResponseStarted(ctx)
                && hasRetryBudget(ctx);
    }

    /**
     * Tells whether the client response has already started, i.e. it can no longer be replaced
     * with an error response but only be aborted. Besides {@code headWritten()} this considers
     * {@link RequestCtx#responseStarted}, because the in-memory response used for
     * Gateleen-internal requests (local rules, queues, hooks, schedulers) reports
     * {@code headWritten()} only once it is ended; writing an error then would append the error
     * text to the partially streamed body and complete it as if it succeeded.
     *
     * @param ctx the per-request context
     * @return {@code true} if streaming the response to the client has begun
     */
    private static boolean isResponseStarted(RequestCtx ctx) {
        return ctx.responseStarted || ctx.dnReq.response().headWritten();
    }

    /**
     * Remaining time (in milliseconds) until the request-wide deadline established in
     * {@link #handleRequest}, clamped to a minimum of {@code 1} ms so a permitted attempt never
     * requests an unbounded ({@code 0}) timeout. When no deadline is configured
     * ({@code deadlineNanos == 0}) the original per-phase {@code timeoutMs} is
     * returned unchanged.
     *
     * @param ctx the per-request context holding the deadline and the configured timeout
     * @return the timeout in milliseconds to use for the next pool-wait or idle phase
     */
    private static long remainingTimeoutMs(RequestCtx ctx) {
        if (ctx.deadlineNanos == 0) {
            return ctx.timeoutMs;
        }
        long remainingMs = NANOSECONDS.toMillis(ctx.deadlineNanos - System.nanoTime());
        return Math.max(1, remainingMs);
    }

    /**
     * Tells whether enough of the request-wide deadline (see {@link #handleRequest}) is still left to
     * make another attempt worthwhile, i.e. at least {@link #MIN_RETRY_BUDGET_MS}. Always
     * {@code true} when no shared deadline is armed ({@code deadlineNanos == 0}, i.e. no timeout
     * configured); the number of attempts is still capped by the retry policy. Using a small floor (rather than "any time
     * left at all") avoids launching a doomed near-zero-timeout retry that would waste a forwarding
     * attempt.
     *
     * @param ctx the per-request context holding the deadline
     * @return {@code true} if another attempt fits into the remaining request-wide deadline
     */
    private static boolean hasRetryBudget(RequestCtx ctx) {
        if (ctx.deadlineNanos == 0) {
            return true;
        }
        long remainingMs = NANOSECONDS.toMillis(ctx.deadlineNanos - System.nanoTime());
        return remainingMs >= MIN_RETRY_BUDGET_MS;
    }

    /**
     * Tells whether the given HTTP method is <em>idempotent</em> in the sense of
     * <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-4.2.2">RFC 7231 §4.2.2</a>,
     * i.e. sending the identical request N&gt;1 times has the same effect on the server as sending it
     * once. Such methods are the only ones we are willing to replay automatically.
     * <p>
     * {@code GET}, {@code HEAD}, {@code PUT}, {@code DELETE}, {@code OPTIONS} and {@code TRACE} are
     * idempotent and therefore retryable. Deliberately excluded are {@code POST}, {@code PATCH} and
     * {@code CONNECT}: they are not idempotent, so an automatic retry could duplicate a side effect
     * (a second resource creation, a second partial update, etc.).
     *
     * @param method the request method to classify.
     * @return {@code true} if {@code method} is idempotent and hence safe to retry.
     */
    private static boolean isIdempotent(HttpMethod method) {
        return HttpMethod.GET.equals(method)
                || HttpMethod.HEAD.equals(method)
                || HttpMethod.PUT.equals(method)
                || HttpMethod.DELETE.equals(method)
                || HttpMethod.OPTIONS.equals(method)
                || HttpMethod.TRACE.equals(method);
    }

    private void onNewRequestCompleteNoThrow(AsyncResult<HttpClientRequest> ev, RequestCtx ctx) {
        try {
            onNewRequestComplete(ev, ctx);
        } catch (RuntimeException ex) {
            /* catch-all unhandled exceptions. Usually, this code SHOULD NOT be reached!
             * (If it is reached, GO FIX THE METHOD WE CALL ABOVE!) This is our
             * last-resort/best-effort handler, to hopefully have some better error
             * logs than just "Connection was closed" without any context. */
            String dbgHint = "findme_qh398338h9ut";
            ctx.log.warn("{}: {}: {}, {} -fwd-> {}", dbgHint, ex.getMessage(), ctx.uniqueId, ctx.dnReq.path(),
                    ctx.targetUri, ctx.log.isDebugEnabled() ? ex : null);
            if (finishRequest(ctx, INTERNAL_SERVER_ERROR.getStatusCode(), dbgHint + ": " + ex.getMessage())) {
                tryRespondWithInternalServerError(ctx.dnReq.response(), ctx.log, dbgHint);
            }
        }
    }

    private void onNewRequestComplete(AsyncResult<HttpClientRequest> event, RequestCtx ctx) {
        ctx.dnReq.resume();
        if (event.failed()) {
            ctx.log.warn("Problem to request {}: {}", ctx.targetUri, event.cause());
            finishRequest(ctx, SERVICE_UNAVAILABLE.getStatusCode(), "Problem to request: " + event.cause());
            final HttpServerResponse response = ctx.dnReq.response();
            response.setStatusCode(StatusCode.SERVICE_UNAVAILABLE.getStatusCode());
            response.setStatusMessage(StatusCode.SERVICE_UNAVAILABLE.getStatusMessage());
            response.end();
            return;
        }
        ctx.upReq = event.result();
        HttpClientRequest upstreamRequest = ctx.upReq;
        /*
         * Request exceptions (e.g. a failed body upload) used to be logged at WARN plus an ERROR
         * entry. Whether this attempt is retried is decided by the response future, which may run
         * before or after this exception handler; so record the exception and log it once the
         * attempt has definitely failed without a retry.
         */
        final AtomicReference<Throwable> requestFailure = new AtomicReference<>();
        final AtomicBoolean attemptFailedFinally = new AtomicBoolean();
        final AtomicBoolean requestFailureLogged = new AtomicBoolean();
        final Runnable logRequestFailure = () -> {
            Throwable failure = requestFailure.get();
            if (failure != null && attemptFailedFinally.get() && requestFailureLogged.compareAndSet(false, true)) {
                logUpstreamRequestFailure(ctx, failure);
            }
        };
        upstreamRequest.exceptionHandler(ex -> {
            if (ctx.upRes == null) {
                requestFailure.compareAndSet(null, ex);
                logRequestFailure.run();
            }
            if (!ctx.finished.get() && ctx.upReq == upstreamRequest) {
                // Before a response, the response future (or drain handler) owns retry/error
                // handling. Do not write a competing 502 from the request exception callback.
                if (ctx.upRes == null) {
                    ctx.log.debug("Upstream request failed for {}", ctx.targetUri, ex);
                } else if (finishRequest(ctx, ctx.upRes.statusCode(), "Upstream request failed: " + ex.getMessage())) {
                    logUpstreamRequestFailure(ctx, ex);
                    if (ctx.dnReq.response().ended() || isResponseStarted(ctx)) {
                        ctx.log.info("{}: Response already written. Not sure about the state. Closing server connection for stability reason",
                                ctx.targetUri);
                        ctx.dnReq.response().close();
                    } else {
                        respondError(ctx.dnReq, ex instanceof TimeoutException ? StatusCode.TIMEOUT : SERVICE_UNAVAILABLE);
                    }
                    upstreamRequest.reset(0);
                }
            }
        });
        ctx.upReq.response(ev -> {
            if (ctx.finished.get() || ctx.upReq != upstreamRequest) {
                if (ev.succeeded()) {
                    ev.result().resume();
                }
                return;
            }
            if (ev.failed()) {
                ctx.log.warn("Bad upstream response: {}://{}{} {}",
                        rule.getScheme(), ctx.upstream.target, ctx.targetUri, ev.cause().getMessage(),
                        ctx.log.isDebugEnabled() ? ev.cause() : null);
                /*
                 * The upstream failed to deliver a usable response (connection dropped, reset,
                 * timed out while waiting for the response head, ...). Before surrendering with a
                 * 502 we ask mayRetry() whether another attempt is safe and permitted (idempotent
                 * method, buffered/replayable body, attempt budget left, nothing sent downstream
                 * yet). See mayRetry() for the full reasoning behind each condition.
                 *
                 * If a retry is allowed we bump the 1-based attempt counter and re-enter
                 * sendToTarget(ctx), which opens a brand-new upstream connection and replays the
                 * buffered request. We MUST return here so we do not also send the 502 below for
                 * this (now superseded) attempt; the fresh attempt owns the outcome from now on.
                 */
                if (mayRetry(ctx, retryPolicy.maxAttemptsForUpstreamFailure(ctx.dnReq.headers(), ctx.log))) {
                    ctx.attempt++;
                    ctx.log.info("Retrying request (attempt {}) to {}://{}{} after upstream failure",
                            ctx.attempt, rule.getScheme(), ctx.upstream.target, ctx.targetUri);
                    sendToTarget(ctx);
                    return;
                }
                /* No (further) retry allowed -> report the upstream failure to the client.
                 * Finish first so metrics are complete before the response becomes observable. */
                if (finishRequest(ctx, BAD_GATEWAY.getStatusCode(), "Bad upstream response: " + ev.cause().getMessage())) {
                    attemptFailedFinally.set(true);
                    logRequestFailure.run();
                    tryRespondWithBadGateway(ctx.dnReq.response(), ctx.log, "findme_49ot58h0inrnu3985h");
                }
                return;
            }
            onUpstreamResponseNoThrow(ev.result(), ctx, "findme_3q908hjq98t");
        });

        // Retry-enabled requests use their remaining deadline; ordinary requests get a fresh
        // idle window after pool acquisition, preserving the original per-phase behaviour.
        // A non-positive timeout disables the deadline; Vert.x requires a positive idle timeout.
        if (ctx.timeoutMs > 0) {
            ctx.upReq.idleTimeout(remainingTimeoutMs(ctx));
        }

        // per https://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.10
        MultiMap headersToForward = ctx.dnReq.headers();
        headersToForward = removeNonForwardHeaders(headersToForward);
        HttpHeaderUtil.mergeHeaders(ctx.upReq.headers(), headersToForward, ctx.targetUri);
        // Retry instructions apply to the hop that retries. Local rules do not retry, so they pass
        // the headers on to the rule handling the rewritten path; other hops consume them so
        // chained Gateleen instances cannot multiply the attempts beyond each instance's ceiling.
        if (!isLocalRule()) {
            ForwarderRetryPolicy.removeRetryHeaders(ctx.upReq.headers());
        }
        if (!ResponseStatusCodeLogUtil.isRequestToExternalTarget(ctx.upstream.target)) {
            ctx.upReq.headers().set(SELF_REQUEST_HEADER, "true");
        }

        if (ctx.uniqueId != null) {
            ctx.upReq.headers().set("x-rp-unique_id", ctx.uniqueId);
        }
        setProfileHeaders(ctx.log, ctx.profileHeaderMap, ctx.upReq);

        if (ctx.authHeader != null) ctx.upReq.headers().set(ctx.authHeader.key(), ctx.authHeader.value());

        final String errorMessage = applyHeaderFunctions(ctx.log, ctx.upReq.headers(), ctx.upstream.target);
        if (errorMessage != null) {
            ctx.log.warn("Problem invoking Header functions: {}", errorMessage);
            finishRequest(ctx, StatusCode.BAD_REQUEST.getStatusCode(), "Problem invoking Header functions: " + errorMessage);
            final HttpServerResponse response = ctx.dnReq.response();
            response.setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
            response.setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
            response.end(errorMessage);
            upstreamRequest.reset(0);
            return;
        }

        /*
         * If no bodyData is available
         * this means, that the request body isn't
         * consumed yet. So we can use the regular
         * request for the data.
         * If the body is already consumed, we use
         * the buffer bodyData.
         */
        if (ctx.bodyData == null) {

            // Gateleen internal requests (e.g. from schedulers or delegates) often have neither "Content-Length" nor "Transfer-Encoding: chunked"
            // header - so we must wait for a body buffer to know: Is there a body or not? Only looking on the headers and/or the http-method is not
            // sustainable to know "has body or not"
            // But: if there is a body, then we need to either setChunked or a Content-Length header (otherwise Vertx complains with an Exception)
            //
            // Setting 'chunked' always has the downside that we use it also for GET, HEAD, OPTIONS etc... Those request methods normally have no body at all
            // But still it's allowed - so they 'could' have one. So using http-method to decide "chunked or not" is also not a sustainable solution.
            //
            // --> we need to wrap the client-Request to catch up the first (body)-buffer and "setChucked(true)" in advance and just-in-time.
            AutomaticChunkedRequestTransfer cReqWrapped = new AutomaticChunkedRequestTransfer(vertx, ctx.upReq, "findme_oi8hju30895jh3itj");

            ctx.dnReq.exceptionHandler(t -> {
                ctx.log.info("Exception during forwarding - closing (forwarding) client connection", t);
                HttpConnection connection = ctx.upReq.connection();
                if (connection != null) {
                    connection.close();
                } else {
                    ctx.log.warn("There's no connection we could close in {}, gateleen wishes your request a happy timeout ({})",
                            ctx.upReq.getClass(), ctx.dnReq.uri());
                }
            });

            final LoggingWriteStream loggingWriteStream = new LoggingWriteStream(cReqWrapped, ctx.loggingHandler, true);
            final Pump pump = Pump.pump(ctx.dnReq, loggingWriteStream);
            if (ctx.dnReq.isEnded()) {
                // since Vert.x 3.6.0 it can happen that requests without body (e.g. a GET) are ended even while in paused-State
                // Setting the endHandler would then lead to an Exception
                // see also https://github.com/eclipse-vertx/vert.x/issues/2763
                // so we now check if the request already is ended before installing an endHandler
                ctx.upReq.send();
            } else {
                ctx.dnReq.endHandler(v -> ctx.upReq.send());
                pump.start();
            }
        } else {
            // we already have the body complete in-memory - so we can use Content-Length header and avoid chunked transfer
            ctx.upReq.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(ctx.bodyData.length()));
            ctx.upReq.send(ctx.bodyData);
        }

        ctx.loggingHandler.request(ctx.upReq.headers());
    }

    private void onUpstreamResponseNoThrow(HttpClientResponse rsp, RequestCtx ctx, String dbgHint) {
        try {
            onUpstreamResponse(rsp, ctx);
        } catch (RuntimeException ex) {
            /* catch-all unhandled exceptions. Usually, this code SHOULD NOT be reached!
             * (If it is reached, GO FIX THE METHOD WE CALL ABOVE!) This is our
             * last-resort/best-effort handler, to hopefully have some better error
             * logs than just "Connection was closed" without any context. */
            ctx.log.warn("findme_bnte4jsfdgj: {}: {}: {}, {} -fwd-> {}",
                    dbgHint, ex.getMessage(), ctx.uniqueId, ctx.dnReq.path(), ctx.targetUri,
                    ctx.log.isDebugEnabled() ? ex : null);
            if (finishRequest(ctx, INTERNAL_SERVER_ERROR.getStatusCode(), dbgHint + ": " + ex.getMessage())) {
                tryRespondWithInternalServerError(ctx.dnReq.response(), ctx.log, dbgHint);
            }
        }
    }

    private void onUpstreamResponse(HttpClientResponse rsp, RequestCtx ctx) {
        long maxAttempts = rsp.statusCode() >= 400 && rsp.statusCode() < 600
                ? retryPolicy.maxAttemptsForResponse(ctx.dnReq.headers(), rsp.statusCode(), ctx.log) : 1;
        if (rsp.statusCode() >= 400 && rsp.statusCode() < 600 && mayRetry(ctx, maxAttempts)) {
            // Drain the rejected response before reusing the connection; never expose it downstream.
            AtomicBoolean completed = new AtomicBoolean();
            Handler<Void> retry = ignored -> {
                if (completed.compareAndSet(false, true)) {
                    if (ctx.finished.get()) {
                        return;
                    }
                    // Draining may have consumed the remaining budget after the response head.
                    if (!mayRetry(ctx, maxAttempts)) {
                        expireRequest(ctx, "Insufficient retry budget after draining HTTP " + rsp.statusCode());
                        return;
                    }
                    ctx.attempt++;
                    ctx.log.info("Retrying request (attempt {}) to {}://{}{} after HTTP {}",
                            ctx.attempt, rule.getScheme(), ctx.upstream.target, ctx.targetUri, rsp.statusCode());
                    sendToTarget(ctx);
                }
            };
            rsp.exceptionHandler(ex -> {
                if (completed.compareAndSet(false, true)
                        && finishRequest(ctx, BAD_GATEWAY.getStatusCode(), "Failed to drain retryable HTTP " + rsp.statusCode() + " response: " + ex.getMessage())) {
                    ctx.log.warn("Failed to drain retryable upstream response for {}", ctx.targetUri, ex);
                    tryRespondWithBadGateway(ctx.dnReq.response(), ctx.log, "Failed to drain retryable response");
                }
            });
            rsp.endHandler(retry);
            rsp.handler(this::doNothing);
            if (isConnCloseHdrSet(rsp)) {
                shutdownSinkNoThrow(ctx.upReq);
            }
            rsp.resume();
            return;
        }
        ctx.dnRsp = ctx.dnReq.response();

        ctx.upRes = rsp;
        ctx.upRes.exceptionHandler(ex -> onUpstreamError(ex, ctx.dnReq, () -> ctx.upRes.request().getURI()));
        ctx.loggingHandler.setResponse(ctx.upRes);
        ctx.dnRsp.setStatusCode(ctx.upRes.statusCode());
        ctx.dnRsp.setStatusMessage(ctx.upRes.statusMessage());

        /* If upstream announces to close the connection after this response, we
         * MUST NOT try to re-use this connection (through the connection pool).
         * So ensure (by closing it) that pool is not going to re-use it.
         * See also:
         * https://github.com/swisspost/gateleen/issues/737
         * https://jira.post.ch/browse/SDCISA-22791
         * https://jira.post.ch/browse/SDCISA-17956?focusedId=2392370&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-2392370
         * https://jira.post.ch/browse/SDCISA-21348   */
        if (isConnCloseHdrSet(ctx.upRes)) {
            shutdownSinkNoThrow(ctx.upReq);
        }

        int statusCode = ctx.upRes.statusCode();

        // translate with header info
        int translatedStatus = Translator.translateStatusCode(statusCode, ctx.dnReq.headers());

        // nothing changed?
        if (statusCode == translatedStatus) {
            translatedStatus = Translator.translateStatusCode(statusCode, rule, ctx.log);
        }

        boolean translated = (statusCode != translatedStatus);

        // set the statusCode (if nothing happened, it will remain the same)
        statusCode = translatedStatus;

        ctx.dnRsp.setStatusCode(statusCode);

        if (translated) {
            ctx.dnRsp.setStatusMessage(HttpResponseStatus.valueOf(statusCode).reasonPhrase());
        }

        // Add received headers to original request but remove headers that should not get forwarded.
        MultiMap headersToForward = ctx.upRes.headers();
        headersToForward = removeNonForwardHeaders(headersToForward);
        List<String> transferEncodings = headersToForward.getAll(HttpHeaders.TRANSFER_ENCODING).stream()
                .flatMap(value -> Arrays.stream(value.split(",", -1)))
                .map(String::trim)
                .collect(Collectors.toList());
        boolean upstreamResponseIsChunked = transferEncodings.size() == 1
                && "chunked".equalsIgnoreCase(transferEncodings.get(0));
        if (!transferEncodings.isEmpty() && !upstreamResponseIsChunked) {
            ctx.log.warn("Unsupported transfer encoding from '{}': {}", ctx.targetUri, transferEncodings);
            ctx.upRes.resume();
            finishRequest(ctx, BAD_GATEWAY.getStatusCode(), "Unsupported transfer encoding " + transferEncodings);
            respondError(ctx.dnReq, BAD_GATEWAY);
            return;
        }
        // Do not forward transfer framing. Enable chunked encoding only if the upstream
        // response actually yields payload bytes; otherwise Vert.x would emit an empty
        // chunked response.
        HttpHeaderUtil.mergeHeaders(ctx.dnRsp.headers(), headersToForward, ctx.targetUri);
        if (ctx.profileHeaderMap != null && !ctx.profileHeaderMap.isEmpty()) {
            HttpHeaderUtil.mergeHeaders(ctx.dnRsp.headers(), MultiMap.caseInsensitiveMultiMap().addAll(ctx.profileHeaderMap), ctx.targetUri);
        }
        // remove transfer-encoding from downstream response headers if existed, because we will handle it ourselves (see LoggingWriteStream)
        ctx.dnRsp.headers().remove(HttpHeaders.TRANSFER_ENCODING);

        final LoggingWriteStream loggingWriteStream = new LoggingWriteStream(
                new AutomaticChunkedResponseTransfer(ctx.dnRsp, upstreamResponseIsChunked, "findme_ohv8oh3q9ohj3q"),
                ctx.loggingHandler,
                false);
        final Pump pump = Pump.pump(ctx.upRes, loggingWriteStream);
        try {
            ctx.upRes.endHandler(nothing -> onUpstreamResponseEnd(nothing, ctx));
        } catch (IllegalStateException ex) {
            ctx.log.warn("cRes.endHandler() failed", ex);
            finishRequest(ctx, INTERNAL_SERVER_ERROR.getStatusCode(), "cRes.endHandler() failed: " + ex.getMessage());
            respondError(ctx.dnReq, INTERNAL_SERVER_ERROR);
            return;
        }
        // The backend response is accepted and will be streamed: complete the metrics now with the
        // backend status. Rejections above complete them with Gateleen's error status instead.
        completeRequestMetrics(ctx, ctx.upRes.statusCode());
        ctx.responseStarted = true;
        pump.start();

        Consumer<String> unpump = (String dbgHint2) -> {
            // disconnect the clientResponse from the Pump and resume this (probably paused-by-pump) stream to keep it alive
            pump.stop();
            try {
                ctx.upRes.handler(this::doNothing);
            } catch (IllegalStateException ieks) {
                /* Q: What is this catch for?
                 * A: https://github.com/eclipse-vertx/vert.x/blob/4.5.2/src/main/java/io/vertx/core/http/impl/HttpClientResponseImpl.java#L150
                 *    Actually we do not care about the data anymore. But we have to
                 *    ensure that the response is consumed. So we have to try to add that
                 *    handler. If it fails, we already are in our desired state. */
                ctx.log.trace("{}: {}", dbgHint2, ieks.getMessage(), ieks);
            }
            ctx.upRes.resume(); // resume the (probably paused) stream
        };

        ctx.upRes.exceptionHandler(exception -> {
            if (!finishRequest(ctx, ctx.upRes.statusCode(), "Failed to read upstream response: " + exception.getMessage())) {
                return;
            }
            LOG.warn("Failed to read upstream response for '{} {}'", ctx.dnReq.method(), ctx.targetUri, exception);
            unpump.accept("findme_nbroih3to9hj");
            error("Problem with backend: " + exception.getMessage(), ctx);
            if (isResponseStarted(ctx)) {
                ctx.dnReq.response().close();
            } else {
                respondError(ctx.dnReq, INTERNAL_SERVER_ERROR);
            }
        });

        HttpConnection connection = ctx.dnReq.connection();
        if (connection != null) {
            /* TODO WARN: there are some impls, which JUST IGNORE our handler
             *      registration, aka they break the promised API contract! */
            connection.closeHandler((Void v) -> {
                // Set before finishing, so a concurrently completing response end sees it.
                ctx.finishedByClientClose = !ctx.finished.get();
                finishRequest(ctx, ctx.upRes.statusCode(), "Client connection closed before the response was completed");
                unpump.accept("findme_boiuhjoq3uh98r");
            });
        } else {
            ctx.log.warn("TODO No way to call 'unpump.run()' in the right moment. As there seems"
                    + " to be no event we could register a handler for. Gateleen wishes you"
                    + " some happy timeouts ({})", ctx.dnReq.uri());
        }
    }

    /**
     * Called when the upstream response was received completely. Logs the response (exactly once,
     * also if the request was already finished by a client disconnect) and, if this call finishes
     * the request, ends the downstream response. The after handler is called for a 2xx response,
     * also when the client disconnected mid-response (the backend did process the request; this
     * matches the behaviour before retries existed), but not after a request-wide deadline expiry.
     *
     * @param nothing1 unused
     * @param ctx      the per-request context
     */
    private void onUpstreamResponseEnd(Void nothing1, RequestCtx ctx) {
        logResponseOnce(ctx);
        if (!finishRequest(ctx, ctx.upRes.statusCode(), null)) {
            if (ctx.finishedByClientClose && is2xx(ctx.upRes.statusCode())) {
                callAfterHanderIfExists(ctx);
            }
            return;
        }
        try {
            ctx.dnRsp.end();
            // if everything is fine, we call the after handler
            if (is2xx(ctx.upRes.statusCode())) {
                callAfterHanderIfExists(ctx);
            }
            ResponseStatusCodeLogUtil.debug(ctx.dnReq, StatusCode.fromCode(ctx.dnRsp.getStatusCode()), Forwarder.class);
        } catch (IllegalStateException ex) {
            ctx.log.debug("findme_q3985hjg3: ignore because maybe already closed: {}", ctx.dnReq.path(), ex);
        }
    }

    /**
     * Schedules {@link LoggingHandler#log()} for the received response exactly once per request,
     * whichever terminal path (regular end or deadline expiry) gets there first.
     *
     * @param ctx the per-request context holding the logging handler
     */
    private void logResponseOnce(RequestCtx ctx) {
        if (ctx.responseLogged.compareAndSet(false, true)) {
            vertx.runOnContext(nothing -> ctx.loggingHandler.log());
        }
    }

    private void onUpstreamError(Throwable exOrig, HttpServerRequest dwnstrmReq, Supplier<String> getUpstreamRequestUri) {
        String errorId = "error_aeuthaeower_" + nextErrorId.getAndIncrement();
        String upstrmReqUri;
        try {
            upstrmReqUri = getUpstreamRequestUri.get();
        } catch (RuntimeException exUseless) {
            LOG.debug("{}", exUseless.getMessage(), LOG.isTraceEnabled() ? exUseless : null);
            upstrmReqUri = "null";
        }
        LOG.error("{}: {} ({})", upstrmReqUri, exOrig.getMessage(), errorId, LOG.isDebugEnabled() ? exOrig : null);
        try {
            HttpServerResponse dwnstrmRsp = dwnstrmReq.response();
            dwnstrmRsp.setStatusCode(502);
            dwnstrmRsp.end("For details, search gateleen logs for\n" + errorId + "\n");
        } catch (RuntimeException exAlreadySent) {
            LOG.debug("{}: {}", dwnstrmReq.uri(), exAlreadySent.getMessage(), LOG.isTraceEnabled() ? exAlreadySent : null);
        }
    }

    /**
     * Returns `true` if there's a HTTP `Connection` header present carrying the
     * `close` token.
     */
    private boolean isConnCloseHdrSet(HttpClientResponse rsp) {
        List<String> connectionHdr = rsp.headers().getAll(HttpHeaders.CONNECTION);
        for (String hdrVal : connectionHdr) {
            if ("close".equalsIgnoreCase(hdrVal)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Do a <a href="https://pubs.opengroup.org/onlinepubs/009695399/functions/shutdown.html">shutdown(req, SHUT_WR)</a>
     * and don't care about {@link RuntimeException}s.
     */
    private void shutdownSinkNoThrow(HttpClientRequest req) {
        try {
            shutdownSink(req);
        } catch (RuntimeException ex) {
            CharSequence details = " (use TRACE level for details)";
            if (LOG.isTraceEnabled()) {
                var buf = new StringBuilder(256).append(": Forwarded request was:\n");
                details = appendAsDbgString(buf, req);
            }
            LOG.debug("{}{}", ex.getMessage(), details, LOG.isTraceEnabled() ? ex : null);
        }
    }

    /**
     * Do a <a href="https://pubs.opengroup.org/onlinepubs/009695399/functions/shutdown.html">shutdown(req, SHUT_WR)</a>.
     */
    private void shutdownSink(HttpClientRequest req) {
        HttpConnection conn = req.connection();
        if (conn == null) {
            /* Q: null-check? What for?
             * A: https://github.com/swisspost/gateleen/blob/v2.1.41/gateleen-core/src/main/java/org/swisspush/gateleen/core/http/LocalHttpClientRequest.java#L925-L929   */
            CharSequence details = " (use TRACE level for details)";
            if (LOG.isTraceEnabled()) {
                var buf = new StringBuilder(256).append(": Forwarded request was:\n");
                details = appendAsDbgString(buf, req);
            }
            LOG.debug("There's nothing we could close{}", details,
                    LOG.isTraceEnabled() ? new NullPointerException("connection") : null);
            return;
        }
        /* Damn! I just need `shutdown(s, SHUT_WR)`! But now I need to guess-around
         * with some unneccessary timeouts :(  */
        /* Q: Why evaluating the timeout value this way?
         * A: https://github.com/swisspost/gateleen/pull/744#discussion_r3049404951   */
        long vertxConnShutdownTimeoutMs;
        String timeoutFromReqHdrAsStr = req.headers().get("x-timeout");
        if (timeoutFromReqHdrAsStr != null) {
            /* try to use timeout from the most recent request */
            try {
                /* no documentation anywhere, so we have to assume header is in millis? */
                vertxConnShutdownTimeoutMs = parseLong(timeoutFromReqHdrAsStr);
            } catch (NumberFormatException ex) {
                LOG.info("parseLong({}): {}", timeoutFromReqHdrAsStr, ex.getMessage(), LOG.isDebugEnabled() ? ex : null);
                vertxConnShutdownTimeoutMs = (rule != null) ? rule.getTimeout() : 0;
            }
        } else {
            /* if most recent request had no value, fallback to the value configured
             * via rule. As there's no documentation anywhere, we have to assume
             * getter returns millis? */
            vertxConnShutdownTimeoutMs = (rule != null) ? rule.getTimeout() : 0;
        }
        if (vertxConnShutdownTimeoutMs < 1000) {
            LOG.debug("Refuse tailResponseTimeoutMs {}ms, will go with at least 1000ms", vertxConnShutdownTimeoutMs);
            vertxConnShutdownTimeoutMs = 1000;
        }
        /* Vertx API seems not to have any way to do what we need (eg `shutdown(req, SHUT_WR)`)
         * So we have to use this less accurate alternative, which is similar to a
         * `shutdown(req, SHUT_RDWR)`, but it will await pending data on the
         * current request-response cycle (or up to the timeout) before doing so.
         * Not exactly what we wanted, but hopefully "good enough".
         * The other alternative (just calling `close()`) is also a bad option,
         * as it causes undesired effects like
         * https://jira.post.ch/browse/MSOP-6438
         * under edge-case conditions.  */
        conn.shutdown(vertxConnShutdownTimeoutMs, MILLISECONDS).onFailure((Throwable ex) -> {
            LOG.info("{}", ex, LOG.isDebugEnabled() ? ex : null);
        });
    }

    /**
     * Appends given `req` to given `buf` in a format similar to how it would
     * look like when encoded as an HTTP message.
     * @return
     *      Fluent reference to given `buf`
     */
    private <T extends StringBuilder> T appendAsDbgString(T buf, HttpClientRequest req) {
        /* unfortunately, `req` seems NOT to give us any information about what
         * http proto version was used. So we WON'T fill it in here. */
        buf.append(req.getMethod()).append(' ').append(req.path())/*.append(" HTTP/X.Y")*/.append('\n');
        for (var hdr : req.headers()) {
            buf.append(hdr.getKey()).append(": ").append(hdr.getValue()).append('\n');
        }
        return buf;
    }

    private void tryRespondWithInternalServerError(HttpServerResponse rsp, Logger log, String dbgHint) {
        tryRespondWith(rsp, INTERNAL_SERVER_ERROR.getStatusCode(), INTERNAL_SERVER_ERROR.getStatusMessage(), log, dbgHint);
    }

    private void tryRespondWithBadGateway(HttpServerResponse rsp, Logger log, String dbgHint) {
        tryRespondWith(rsp, BAD_GATEWAY.getStatusCode(), BAD_GATEWAY.getStatusMessage(), log, dbgHint);
    }

    private void tryRespondWithServiceUnavailable(HttpServerResponse rsp, Logger log, String dbgHint) {
        tryRespondWith(rsp, SERVICE_UNAVAILABLE.getStatusCode(), SERVICE_UNAVAILABLE.getStatusMessage(), log, dbgHint);
    }

    private void tryRespondWith(HttpServerResponse response, int statusCode, String statusMsg, Logger log, String dbgHint) {
        assert statusCode >= 200 && statusCode <= 599 : statusCode;
        assert statusMsg != null : "statusMsg != null";
        try {
            response.setStatusCode(statusCode);
            response.setStatusMessage(statusMsg);
            if (statusCode >= 200 && statusCode <= 299) {
                response.end();
            } else {
                response.end(dbgHint.endsWith("\n") ? dbgHint : (dbgHint + "\n"));
            }
        } catch (IllegalStateException iex) {
            log.debug("{}", dbgHint, iex);
        }
    }

    private Future<Optional<AuthHeader>> maybeAuthenticate(Rule rule) {
        if (authStrategy == null) {
            return Future.succeededFuture(Optional.empty());
        }
        return authStrategy.authenticate(rule).compose(authHeader -> Future.succeededFuture(Optional.of(authHeader)));
    }

    private void setProfileHeaders(Logger log, Map<String, String> profileHeaderMap, HttpClientRequest cReq) {
        if (profileHeaderMap != null && !profileHeaderMap.isEmpty()) {
            log.debug("Putting partial profile to header for the backend request (profileHeaderMap).");
            for (Map.Entry<String, String> entry : profileHeaderMap.entrySet()) {
                cReq.headers().set(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Logs an ERROR for this request, prefixed with the upstream URL it was forwarded to.
     *
     * @param message the error description
     * @param ctx     the per-request context (provides the resolved upstream target and URI)
     */
    private void error(String message, RequestCtx ctx) {
        RequestLoggerFactory.getLogger(Forwarder.class, ctx.dnReq)
                .error(rule.getScheme() + "://" + ctx.upstream.target + ctx.targetUri + " " + message);
    }

    /**
     * Logs a failed upstream request the way it was logged before retries existed: a WARN
     * {@code Failed to '<method> <uri>'} (without stack trace for connect timeouts) plus an ERROR
     * entry, or only an ERROR {@code Timeout} for a {@link TimeoutException}. The ERROR entry is
     * skipped for retried requests, because the retry summary already logged one.
     *
     * @param ctx the per-request context
     * @param ex  the exception the upstream request failed with
     */
    private void logUpstreamRequestFailure(RequestCtx ctx, Throwable ex) {
        boolean errorEntry = ctx.attempt <= 1;
        if (ex instanceof TimeoutException) {
            if (errorEntry) {
                error("Timeout", ctx);
            }
            return;
        }
        if (ex instanceof ConnectTimeoutException) {
            ctx.log.warn("Failed to '{} {}'", ctx.dnReq.method(), ctx.targetUri);
        } else {
            ctx.log.warn("Failed to '{} {}'", ctx.dnReq.method(), ctx.targetUri, ex);
        }
        if (errorEntry) {
            error(ex.getMessage(), ctx);
        }
    }

    private void callAfterHanderIfExists(RequestCtx ctx) {
        Handler<Void> afterHandler = ctx.afterHandlerRf.getAndSet(null);
        if (afterHandler != null) {
            afterHandler.handle(null);
        }
    }

    private boolean is2xx(int n) {
        return n >= 200 && n <= 299;
    }

    private void doNothing(Object o) {/* Guess why this is empty. */}

    private static class RequestCtx {
        private final AtomicBoolean metricsCompleted = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        /** Guards {@link LoggingHandler#log()} so a received response is logged exactly once. */
        private final AtomicBoolean responseLogged = new AtomicBoolean();
        /** Set when the client closed the connection before the request was otherwise finished. */
        private volatile boolean finishedByClientClose = false;
        private long deadlineTimerId = -1;
        private long poolWaitTimerId = -1;
        /** downstream request (aka the incoming request made by some other client) */
        private final HttpServerRequest dnReq;
        /** downstream response (aka response intended for a client which did call us) */
        private HttpServerResponse dnRsp = null;
        /** upstream request (aka the request WE initiated to some server) */
        public HttpClientRequest upReq;
        /** upstream response (aka the response from our external server we've called) */
        private HttpClientResponse upRes;
        private final Logger log;
        /** Backend resolved once per request (host/port wildcards), reused by every retry. */
        private final UpstreamTarget upstream;
        private final String targetUri;
        private final Long startTime;
        private final Timer.Sample timerSample;
        private final Map<String, String> profileHeaderMap;
        private final LoggingHandler loggingHandler;
        private final AtomicReference<Handler<Void>> afterHandlerRf;
        private final String timeout;
        private final long timeoutMs;
        private final String uniqueId;
        private final AuthHeader authHeader;
        private final Buffer bodyData;
        /**
         * Number of forwarding attempts already started for this request, 1-based: it is {@code 1}
         * for the very first try and is incremented by one immediately before each retry is launched.
         * It is compared against the selected, ceiling-capped retry policy in {@link Forwarder#mayRetry}.
         * Kept on the context (rather than as a local) precisely
         * so that its value survives across the successive {@link Forwarder#sendToTarget} invocations
         * of a retried request.
         */
        private long attempt = 1;

        /**
         * Absolute deadline, expressed on the {@link System#nanoTime()} clock, by which the whole
         * forwarded request (initial attempt plus any retries) must be finished. Set once in
         * {@link Forwarder#handleRequest} when retries and a timeout are enabled, never changed afterwards,
         * so it bounds the total request lifetime rather than each individual attempt. A value of
         * {@code 0} disables the shared deadline; ordinary per-phase idle/pool timeouts still apply.
         */
        private long deadlineNanos = 0;

        /**
         * Set right before the accepted upstream response starts streaming into {@link #dnRsp}.
         * From then on the client response can no longer be replaced with an error, even if the
         * downstream implementation does not report it via {@code headWritten()} yet (the
         * in-memory response of Gateleen-internal requests does so only once it is ended).
         * Only accessed on the request's event loop.
         */
        private boolean responseStarted;

        private RequestCtx(
                HttpServerRequest dnReq,
                Logger log,
                UpstreamTarget upstream,
                String targetUri,
                Long startTime,
                Timer.Sample timerSample,
                Map<String, String> profileHeaderMap,
                LoggingHandler loggingHandler,
                Handler<Void> afterHandler,
                String timeout,
                long timeoutMs,
                String uniqueId,
                AuthHeader authHeader,
                Buffer bodyData
        ) {
            this.dnReq = dnReq;
            this.log = log;
            this.upstream = upstream;
            this.targetUri = targetUri;
            this.startTime = startTime;
            this.timerSample = timerSample;
            this.profileHeaderMap = profileHeaderMap;
            this.loggingHandler = loggingHandler;
            this.afterHandlerRf = new AtomicReference<>(afterHandler);
            this.timeout = timeout;
            this.timeoutMs = timeoutMs;
            this.uniqueId = uniqueId;
            this.authHeader = authHeader;
            this.bodyData = bodyData;
        }
    }

    /**
     * Upstream backend resolved once per request (including host/port wildcards). Kept per request
     * instead of in Forwarder fields so concurrent requests cannot change the target of a retry.
     */
    private static final class UpstreamTarget {
        private final String host;
        private final int port;
        /** {@code host:port}, as used for logging, metrics and the default Host header. */
        private final String target;

        /**
         * @param host the upstream host
         * @param port the upstream port
         */
        private UpstreamTarget(String host, int port) {
            this.host = host;
            this.port = port;
            this.target = host + ":" + port;
        }
    }

}

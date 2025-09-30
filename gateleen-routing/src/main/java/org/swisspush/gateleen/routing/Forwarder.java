package org.swisspush.gateleen.routing;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.Pump;
import io.vertx.core.streams.WriteStream;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.swisspush.gateleen.core.util.HttpHeaderUtil.removeNonForwardHeaders;
import static org.swisspush.gateleen.core.util.StatusCode.INTERNAL_SERVER_ERROR;


/**
 * Forwards requests to the backend.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class Forwarder extends AbstractForwarder {

    private final String userProfilePath;
    private final HttpClient client;
    private final Pattern urlPattern;
    private String target;
    private int port;
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
    private Timer forwardTimer;
    private MeterRegistry meterRegistry;

    public Forwarder(Vertx vertx, HttpClient client, Rule rule, final ResourceStorage storage,
                     LoggingResourceManager loggingResourceManager, LogAppenderRepository logAppenderRepository,
                     @Nullable MonitoringHandler monitoringHandler, String userProfilePath, @Nullable AuthStrategy authStrategy) {
        super(rule, loggingResourceManager, logAppenderRepository, monitoringHandler);
        this.vertx = vertx;
        this.client = client;
        this.rule = rule;
        this.loggingResourceManager = loggingResourceManager;
        this.logAppenderRepository = logAppenderRepository;
        this.monitoringHandler = monitoringHandler;
        this.storage = storage;
        this.urlPattern = Pattern.compile(rule.getUrlPattern());
        this.target = rule.getHost() + ":" + rule.getPort();
        this.userProfilePath = userProfilePath;
        this.authStrategy = authStrategy;
    }

    /**
     * Sets the MeterRegistry for this Forwarder.
     * If the provided MeterRegistry is not null, it initializes the forwardCounter
     * with the appropriate metric name, description, and tags.
     *
     * @param meterRegistry the MeterRegistry to set
     */
    @Override
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        if (meterRegistry != null) {
            forwardTimer = Timer.builder(FORWARDS_METRIC_NAME)
                    .description(FORWARDS_METRIC_DESCRIPTION)
                    .publishPercentiles(0.75, 0.95)
                    .tag(FORWARDER_METRIC_TAG_METRICNAME, metricNameTag)
                    .tag(FORWARDER_METRIC_TAG_TYPE, getRequestTarget(target))
                    .register(meterRegistry);
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
     * @param ctx      - the original request context
     * @param bodyData - a buffer with the body data, null if the request
     *                 was not yet consumed
     */
    public void handle(final RoutingContext ctx, final Buffer bodyData, @Nullable final Handler<Void> afterHandler) {
        HttpServerRequest req = ctx.request();
        final Logger log = RequestLoggerFactory.getLogger(Forwarder.class, req);

        if (rule.hasHeadersFilterPattern() && !doHeadersFilterMatch(ctx.request())) {
            ctx.next();
            return;
        }
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
        target = host + ":" + port;

        if (monitoringHandler != null) {
            monitoringHandler.updateRequestsMeter(target, req.uri());
            monitoringHandler.updateRequestPerRuleMonitoring(req, rule.getMetricName());
        }
        final String targetUri = urlPattern.matcher(req.uri()).replaceFirst(rule.getPath()).replaceAll("\\/\\/", "/");
        log.debug("Forwarding request: {} to {}://{} with rule {}", req.uri(), rule.getScheme(), target + targetUri, rule.getRuleIdentifier());
        final String userId = extractUserId(req, log);
        req.pause(); // pause the request to avoid problems with starting another async request (storage)

        maybeAuthenticate(rule).onComplete(event -> {
            if (event.failed()) {
                req.resume();
                log.error("Failed to authenticate request. Cause: {}", event.cause().getMessage());
                respondError(req, StatusCode.UNAUTHORIZED);
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
                    handleRequest(req, bodyData, targetUri, log, profileHeaderMap, authHeader, afterHandler);
                });
            } else {
                handleRequest(req, bodyData, targetUri, log, null, authHeader, afterHandler);
            }
        });
    }

    private void handleForwardDurationMetrics(Timer.Sample timerSample) {
        if (timerSample != null && forwardTimer != null) {
            timerSample.stop(forwardTimer);
        }
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
     * Execute the HeaderFunctions chain and apply the configured rules headers therefore
     *
     * @param log     The logger to be used
     * @param headers The headers which must be updated according the current forwarders rule
     * @return null if everything is properly done or a error message if something went wrong.
     */
    String applyHeaderFunctions(final Logger log, MultiMap headers) {
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

    private void handleRequest(final HttpServerRequest req, final Buffer bodyData, final String targetUri,
                               final Logger log, final Map<String, String> profileHeaderMap,
                               Optional<AuthHeader> authHeader, @Nullable final Handler<Void> afterHandler
    ) {
        /* collect stuff we need */
        final LoggingHandler loggingHandler = new LoggingHandler(loggingResourceManager, logAppenderRepository, req, vertx.eventBus());
        String timeout = req.headers().get("x-timeout");
        String uniqueId = req.headers().get("x-rp-unique_id");
        Timer.Sample timerSample = (meterRegistry == null) ? null : Timer.start(meterRegistry);
        Long startTime = (monitoringHandler == null) ? null
                : monitoringHandler.startRequestMetricTracking(rule.getMetricName(), req.uri());
        /* bundle it into a handy context */
        RequestCtx ctx = new RequestCtx(
                req, log, targetUri, startTime, timerSample, profileHeaderMap, loggingHandler,
                afterHandler, timeout, uniqueId, authHeader.orElse(null), bodyData);
        /* initiate request to target server */
        client.request(req.method(), port, rule.getHost(), ctx.targetUri,
                ev -> onNewRequestCompleteNoThrow(ev, ctx));
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
            tryRespondWithInternalServerError(ctx.dnReq.response(), ctx.log, dbgHint);
        }
    }

    private void onNewRequestComplete(AsyncResult<HttpClientRequest> event, RequestCtx ctx) {
        ctx.dnReq.resume();
        if (event.failed()) {
            ctx.log.warn("Problem to request {}: {}", ctx.targetUri, event.cause());
            handleForwardDurationMetrics(ctx.timerSample);
            final HttpServerResponse response = ctx.dnReq.response();
            response.setStatusCode(StatusCode.SERVICE_UNAVAILABLE.getStatusCode());
            response.setStatusMessage(StatusCode.SERVICE_UNAVAILABLE.getStatusMessage());
            response.end();
            return;
        }
        ctx.upReq = event.result();
        ctx.upReq.exceptionHandler(ex -> onUpstreamError(ex, ctx.dnReq, ctx.upReq::getURI));
        ctx.upReq.response(ev -> onUpstreamResponseNoThrow(ev, ctx, "findme_3q908hjq98t"));

        if (ctx.timeout != null) {
            ctx.upReq.idleTimeout(Long.parseLong(ctx.timeout));
        } else {
            ctx.upReq.idleTimeout(rule.getTimeout());
        }

        // per https://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.10
        MultiMap headersToForward = ctx.dnReq.headers();
        headersToForward = removeNonForwardHeaders(headersToForward);
        HttpHeaderUtil.mergeHeaders(ctx.upReq.headers(), headersToForward, ctx.targetUri);
        if (!ResponseStatusCodeLogUtil.isRequestToExternalTarget(target)) {
            ctx.upReq.headers().set(SELF_REQUEST_HEADER, "true");
        }

        if (ctx.uniqueId != null) {
            ctx.upReq.headers().set("x-rp-unique_id", ctx.uniqueId);
        }
        setProfileHeaders(ctx.log, ctx.profileHeaderMap, ctx.upReq);

        if (ctx.authHeader != null) ctx.upReq.headers().set(ctx.authHeader.key(), ctx.authHeader.value());

        final String errorMessage = applyHeaderFunctions(ctx.log, ctx.upReq.headers());
        if (errorMessage != null) {
            ctx.log.warn("Problem invoking Header functions: {}", errorMessage);
            final HttpServerResponse response = ctx.dnReq.response();
            response.setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
            response.setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
            response.end(errorMessage);
            return;
        }

        installExceptionHandler(ctx.dnReq, ctx.targetUri, ctx.startTime, ctx.timerSample, ctx.upReq);

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
            WriteStream<Buffer> cReqWrapped = new WriteStream<>() {
                private boolean firstBuffer = true;

                @Override
                public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
                    ctx.upReq.exceptionHandler(handler);
                    return this;
                }

                @Override
                public Future<Void> write(Buffer data) {
                    // only now we know for sure that there IS a body.
                    if (firstBuffer) {
                        // avoid multiple calls due to a 'syncronized' block in HttpClient's implementation
                        firstBuffer = false;
                        ctx.upReq.setChunked(true);
                    }
                    return ctx.upReq.write(data);
                }

                @Override
                public void write(Buffer data, Handler<AsyncResult<Void>> handler) {
                    write(data).onComplete(handler);
                }

                @Override
                public Future<Void> end() {
                    Promise<Void> promise = Promise.promise();
                    ctx.upReq.send(asyncResult -> {
                                /* TODO wait.. WHAT?!? why is this handler CALLED AGAIN HERE?!? */
                                onUpstreamResponseNoThrow(asyncResult, ctx, "findme_adv2089hj3werag");
                                promise.complete();
                            }
                    );
                    return promise.future();
                }

                @Override
                public void end(Handler<AsyncResult<Void>> handler) {
                    this.end().onComplete(handler);
                }

                @Override
                public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
                    ctx.upReq.setWriteQueueMaxSize(maxSize);
                    return this;
                }

                @Override
                public boolean writeQueueFull() {
                    return ctx.upReq.writeQueueFull();
                }

                @Override
                public WriteStream<Buffer> drainHandler(@Nullable Handler<Void> handler) {
                    ctx.upReq.drainHandler(handler);
                    return this;
                }
            };

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
            ctx.loggingHandler.appendRequestPayload(ctx.bodyData);
            // we already have the body complete in-memory - so we can use Content-Length header and avoid chunked transfer
            ctx.upReq.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(ctx.bodyData.length()));
            ctx.upReq.send(ctx.bodyData);
        }

        ctx.loggingHandler.request(ctx.upReq.headers());
    }

    private void onUpstreamResponseNoThrow(AsyncResult<HttpClientResponse> ev, RequestCtx ctx, String dbgHint) {
        try {
            onUpstreamResponse(ev, ctx);
        } catch (RuntimeException ex) {
            /* catch-all unhandled exceptions. Usually, this code SHOULD NOT be reached!
             * (If it is reached, GO FIX THE METHOD WE CALL ABOVE!) This is our
             * last-resort/best-effort handler, to hopefully have some better error
             * logs than just "Connection was closed" without any context. */
            ctx.log.warn("{}: {}: {}, {} -fwd-> {}", dbgHint, ex.getMessage(), ctx.uniqueId, ctx.dnReq.path(),
                    ctx.targetUri, ctx.log.isDebugEnabled() ? ex : null);
            tryRespondWithInternalServerError(ctx.dnReq.response(), ctx.log, dbgHint);
        }
    }

    private void onUpstreamResponse(AsyncResult<HttpClientResponse> ev, RequestCtx ctx) {
        if (ev.failed()) {
            String dbgHint = "findme_q398hj3g3";
            ctx.log.error("{}: {}://{}{} {}",
                    dbgHint, rule.getScheme(), target, ctx.targetUri, ev.cause().getMessage(),
                    ctx.log.isDebugEnabled() ? ev.cause() : null);
            tryRespondWithInternalServerError(ctx.dnReq.response(), ctx.log, dbgHint);
            return;
        }
        ctx.dnRsp = ctx.dnReq.response();
        if (monitoringHandler != null) {
            monitoringHandler.stopRequestMetricTracking(rule.getMetricName(), ctx.startTime, ctx.dnReq.uri());
        }

        handleForwardDurationMetrics(ctx.timerSample);

        ctx.upRes = ev.result();
        ctx.upRes.exceptionHandler(ex -> onUpstreamError(ex, ctx.dnReq, () -> ctx.upRes.request().getURI()));
        ctx.loggingHandler.setResponse(ctx.upRes);
        ctx.dnRsp.setStatusCode(ctx.upRes.statusCode());
        ctx.dnRsp.setStatusMessage(ctx.upRes.statusMessage());

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
        HttpHeaderUtil.mergeHeaders(ctx.dnRsp.headers(), headersToForward, ctx.targetUri);
        if (ctx.profileHeaderMap != null && !ctx.profileHeaderMap.isEmpty()) {
            HttpHeaderUtil.mergeHeaders(ctx.dnRsp.headers(), MultiMap.caseInsensitiveMultiMap().addAll(ctx.profileHeaderMap), ctx.targetUri);
        }
        // if we receive a chunked transfer then we also use chunked
        // otherwise, upstream must have sent a Content-Length - or no body at all (e.g. for "304 not modified" responses)
        if (ctx.dnRsp.headers().contains(HttpHeaders.TRANSFER_ENCODING, "chunked", true)) {
            ctx.dnRsp.setChunked(true);
        }

        final LoggingWriteStream loggingWriteStream = new LoggingWriteStream(ctx.dnRsp, ctx.loggingHandler, false);
        final Pump pump = Pump.pump(ctx.upRes, loggingWriteStream);
        try {
            ctx.upRes.endHandler(nothing -> onUpstreamResponseEnd(nothing, ctx));
        } catch (IllegalStateException ex) {
            ctx.log.warn("cRes.endHandler() failed", ex);
            respondError(ctx.dnReq, INTERNAL_SERVER_ERROR);
            return;
        }
        pump.start();

        Runnable unpump = () -> {
            // disconnect the clientResponse from the Pump and resume this (probably paused-by-pump) stream to keep it alive
            pump.stop();
            try {
                ctx.upRes.handler(this::doNothing);
            } catch (IllegalStateException ieks) {
                /* Q: What is this catch for?
                 * A: https://github.com/eclipse-vertx/vert.x/blob/4.5.2/src/main/java/io/vertx/core/http/impl/HttpClientResponseImpl.java#L150  */
                ctx.log.debug("findme_q3a908thgj3 {}", ieks.getMessage(), ctx.log.isTraceEnabled() ? ieks : null);
            }
            ctx.upRes.resume(); // resume the (probably paused) stream
        };

        ctx.upRes.exceptionHandler(exception -> {
            LOG.warn("Failed to read upstream response for '{} {}'", ctx.dnReq.method(), ctx.targetUri, exception);
            unpump.run();
            error("Problem with backend: " + exception.getMessage(), ctx.dnReq, ctx.targetUri);
            respondError(ctx.dnReq, INTERNAL_SERVER_ERROR);
        });

        HttpConnection connection = ctx.dnReq.connection();
        if (connection != null) {
            /* TODO WARN: there are some impls, which JUST IGNORE our handler
             *      registration, aka they break the promised API contract! */
            connection.closeHandler((Void v) -> unpump.run());
        } else {
            ctx.log.warn("TODO No way to call 'unpump.run()' in the right moment. As there seems"
                    + " to be no event we could register a handler for. Gateleen wishes you"
                    + " some happy timeouts ({})", ctx.dnReq.uri());
        }
    }

    private void onUpstreamResponseEnd(Void nothing1, RequestCtx ctx) {
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
        vertx.runOnContext(nothing2 -> ctx.loggingHandler.log());
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

    private void tryRespondWithInternalServerError(HttpServerResponse response, Logger log, String dbgHint) {
        try {
            response.setStatusCode(INTERNAL_SERVER_ERROR.getStatusCode());
            response.setStatusMessage(INTERNAL_SERVER_ERROR.getStatusMessage());
            response.end();
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

    private void installExceptionHandler(final HttpServerRequest req, final String targetUri, final Long startTime, @Nullable Timer.Sample timerSample, HttpClientRequest cReq) {
        cReq.exceptionHandler(exception -> {
            if (monitoringHandler != null && startTime != null) {
                monitoringHandler.stopRequestMetricTracking(rule.getMetricName(), startTime, req.uri());
            }

            handleForwardDurationMetrics(timerSample);

            if (exception instanceof TimeoutException) {
                error("Timeout", req, targetUri);
                respondError(req, StatusCode.TIMEOUT);
            } else {
                if (exception instanceof ConnectTimeoutException) {
                    // Don't log stacktrace in case connection timeout
                    LOG.warn("Failed to '{} {}'", req.method(), targetUri);
                } else {
                    LOG.warn("Failed to '{} {}'", req.method(), targetUri, exception);
                }
                error(exception.getMessage(), req, targetUri);
                if (req.response().ended() || req.response().headWritten()) {
                    LOG.info("{}: Response already written. Not sure about the state. Closing server connection for stability reason", targetUri);
                    req.response().close();
                    return;
                }
                respondError(req, StatusCode.SERVICE_UNAVAILABLE);
            }
        });
    }

    private void error(String message, HttpServerRequest request, String uri) {
        RequestLoggerFactory.getLogger(Forwarder.class, request).error(rule.getScheme() + "://" + target + uri + " " + message);
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
        /** downstream request (aka the incoming request made by some other client) */
        private final HttpServerRequest dnReq;
        /** downstream response (aka response intended for a client which did call us) */
        private HttpServerResponse dnRsp = null;
        /** upstream request (aka the request WE initiated to some server) */
        public HttpClientRequest upReq;
        /** upstream response (aka the response from our external server we've called) */
        private HttpClientResponse upRes;
        private final Logger log;
        private final String targetUri;
        private final Long startTime;
        private final Timer.Sample timerSample;
        private final Map<String, String> profileHeaderMap;
        private final LoggingHandler loggingHandler;
        private final AtomicReference<Handler<Void>> afterHandlerRf;
        private final String timeout;
        private final String uniqueId;
        private final AuthHeader authHeader;
        private final Buffer bodyData;

        public RequestCtx(
                HttpServerRequest dnReq,
                Logger log,
                String targetUri,
                Long startTime,
                Timer.Sample timerSample,
                Map<String, String> profileHeaderMap,
                LoggingHandler loggingHandler,
                Handler<Void> afterHandler,
                String timeout,
                String uniqueId,
                AuthHeader authHeader,
                Buffer bodyData
        ) {
            this.dnReq = dnReq;
            this.log = log;
            this.targetUri = targetUri;
            this.startTime = startTime;
            this.timerSample = timerSample;
            this.profileHeaderMap = profileHeaderMap;
            this.loggingHandler = loggingHandler;
            this.afterHandlerRf = new AtomicReference<>(afterHandler);
            this.timeout = timeout;
            this.uniqueId = uniqueId;
            this.authHeader = authHeader;
            this.bodyData = bodyData;
        }
    }

}


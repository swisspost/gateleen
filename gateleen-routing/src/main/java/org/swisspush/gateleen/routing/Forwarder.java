package org.swisspush.gateleen.routing;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
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
import java.util.regex.Pattern;

/**
 * Forwards requests to the backend.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class Forwarder extends AbstractForwarder {

    private String userProfilePath;
    private HttpClient client;
    private Pattern urlPattern;
    private String target;
    private int port;
    private Rule rule;
    private LoggingResourceManager loggingResourceManager;
    private MonitoringHandler monitoringHandler;
    private ResourceStorage storage;
    @Nullable
    private AuthStrategy authStrategy;
    private Vertx vertx;

    private static final String ON_BEHALF_OF_HEADER = "x-on-behalf-of";
    private static final String USER_HEADER = "x-rp-usr";
    private static final String USER_HEADER_PREFIX = "x-user-";
    private static final String ETAG_HEADER = "Etag";
    private static final String IF_NONE_MATCH_HEADER = "if-none-match";
    private static final String SELF_REQUEST_HEADER = "x-self-request";
    private static final String HOST_HEADER = "Host";

    private static final Logger LOG = LoggerFactory.getLogger(Forwarder.class);

    public Forwarder(Vertx vertx, HttpClient client, Rule rule, final ResourceStorage storage,
                     LoggingResourceManager loggingResourceManager, MonitoringHandler monitoringHandler,
                     String userProfilePath, @Nullable AuthStrategy authStrategy) {
        super(rule, loggingResourceManager, monitoringHandler);
        this.vertx = vertx;
        this.client = client;
        this.rule = rule;
        this.loggingResourceManager = loggingResourceManager;
        this.monitoringHandler = monitoringHandler;
        this.storage = storage;
        this.urlPattern = Pattern.compile(rule.getUrlPattern());
        this.target = rule.getHost() + ":" + rule.getPort();
        this.userProfilePath = userProfilePath;
        this.authStrategy = authStrategy;
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
        handle(ctx.request(), null);
    }

    /**
     * Allows to handle a request which was already consumed.
     * If the parameter <code>bodyData</code> is not null, the
     * request was consumed and you can't read the body of the
     * request again.
     *
     * @param req      - the original request
     * @param bodyData - a buffer with the body data, null if the request
     *                 was not yet consumed
     */
    public void handle(final HttpServerRequest req, final Buffer bodyData) {
        final Logger log = RequestLoggerFactory.getLogger(Forwarder.class, req);

        if (handleHeadersFilter(req)) {
            return;
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
        target = rule.getHost() + ":" + port;
        monitoringHandler.updateRequestsMeter(target, req.uri());
        monitoringHandler.updateRequestPerRuleMonitoring(req, rule.getMetricName());
        final String targetUri = urlPattern.matcher(req.uri()).replaceFirst(rule.getPath()).replaceAll("\\/\\/", "/");
        log.debug("Forwarding request: {} to {}://{} with rule {}", req.uri(), rule.getScheme(), target + targetUri, rule.getRuleIdentifier());
        final String userId = extractUserId(req, log);
        req.pause(); // pause the request to avoid problems with starting another async request (storage)

        maybeAuthenticate(rule).onComplete(event -> {
            if(event.failed()) {
                req.resume();
                log.error("Failed to authenticate request. Cause: {}", event.cause().getMessage());
                respondError(req, StatusCode.INTERNAL_SERVER_ERROR);
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
                    handleRequest(req, bodyData, targetUri, log, profileHeaderMap, authHeader);
                });
            } else {
                handleRequest(req, bodyData, targetUri, log, null, authHeader);
            }
        });
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
                               final Logger log, final Map<String, String> profileHeaderMap, Optional<AuthHeader> authHeader) {
        final LoggingHandler loggingHandler = new LoggingHandler(loggingResourceManager, req, vertx.eventBus());

        final String uniqueId = req.headers().get("x-rp-unique_id");
        final String timeout = req.headers().get("x-timeout");
        final long startTime = monitoringHandler.startRequestMetricTracking(rule.getMetricName(), req.uri());

        client.request(req.method(), port, rule.getHost(), targetUri, new Handler<>() {
            @Override
            public void handle(AsyncResult<HttpClientRequest> event) {
                req.resume();

                if (event.failed()) {
                    log.warn("Problem to request {}: {}", targetUri, event.cause());
                    final HttpServerResponse response = req.response();
                    response.setStatusCode(StatusCode.SERVICE_UNAVAILABLE.getStatusCode());
                    response.setStatusMessage(StatusCode.SERVICE_UNAVAILABLE.getStatusMessage());
                    response.end();
                    return;
                }
                HttpClientRequest cReq = event.result();
                final Handler<AsyncResult<HttpClientResponse>> cResHandler = getAsyncHttpClientResponseHandler(req, targetUri, log, profileHeaderMap, loggingHandler, startTime);
                cReq.response(cResHandler);

                if (timeout != null) {
                    cReq.setTimeout(Long.parseLong(timeout));
                } else {
                    cReq.setTimeout(rule.getTimeout());
                }

                // per https://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.10
                MultiMap headersToForward = req.headers();
                headersToForward = HttpHeaderUtil.removeNonForwardHeaders(headersToForward);
                HttpHeaderUtil.mergeHeaders(cReq.headers(), headersToForward, targetUri);
                if (!ResponseStatusCodeLogUtil.isRequestToExternalTarget(target)) {
                    cReq.headers().set(SELF_REQUEST_HEADER, "true");
                }

                if (uniqueId != null) {
                    cReq.headers().set("x-rp-unique-id", uniqueId);
                }
                setProfileHeaders(log, profileHeaderMap, cReq);

                authHeader.ifPresent(authHeaderValue -> {
                    cReq.headers().set(authHeaderValue.key(), authHeaderValue.value());
                });

                final String errorMessage = applyHeaderFunctions(log, cReq.headers());
                if (errorMessage != null) {
                    log.warn("Problem invoking Header functions: {}", errorMessage);
                    final HttpServerResponse response = req.response();
                    response.setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
                    response.setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
                    response.end(errorMessage);
                    return;
                }

                installExceptionHandler(req, targetUri, startTime, cReq);

                /*
                 * If no bodyData is available
                 * this means, that the request body isn't
                 * consumed yet. So we can use the regular
                 * request for the data.
                 * If the body is already consumed, we use
                 * the buffer bodyData.
                 */
                if (bodyData == null) {

                    // Gateleen internal requests (e.g. from scedulers or delegates) often have neither "Content-Length" nor "Transfer-Encoding: chunked"
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
                            cReq.exceptionHandler(handler);
                            return this;
                        }

                        @Override
                        public Future<Void> write(Buffer data) {
                            // only now we know for sure that there IS a body.
                            if (firstBuffer) {
                                // avoid multiple calls due to a 'syncronized' block in HttpClient's implementation
                                firstBuffer = false;
                                cReq.setChunked(true);
                            }
                            return cReq.write(data);
                        }

                        @Override
                        public void write(Buffer data, Handler<AsyncResult<Void>> handler) {
                            write(data).onComplete(handler);
                        }

                        @Override
                        public Future<Void> end() {
                            Promise<Void> promise = Promise.promise();
                            cReq.send(asyncResult -> {
                                        cResHandler.handle(asyncResult);
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
                            cReq.setWriteQueueMaxSize(maxSize);
                            return this;
                        }

                        @Override
                        public boolean writeQueueFull() {
                            return cReq.writeQueueFull();
                        }

                        @Override
                        public WriteStream<Buffer> drainHandler(@Nullable Handler<Void> handler) {
                            cReq.drainHandler(handler);
                            return this;
                        }
                    };

                    req.exceptionHandler(t -> {
                        RequestLoggerFactory
                                .getLogger(Forwarder.class, req)
                                .warn("Exception during forwarding - closing (forwarding) client connection", t);
                        HttpConnection connection = cReq.connection();
                        if (connection != null) {
                            connection.close();
                        }
                    });

                    final LoggingWriteStream loggingWriteStream = new LoggingWriteStream(cReqWrapped, loggingHandler, true);
                    final Pump pump = Pump.pump(req, loggingWriteStream);
                    if (req.isEnded()) {
                        // since Vert.x 3.6.0 it can happen that requests without body (e.g. a GET) are ended even while in paused-State
                        // Setting the endHandler would then lead to an Exception
                        // see also https://github.com/eclipse-vertx/vert.x/issues/2763
                        // so we now check if the request already is ended before installing an endHandler
                        cReq.send(cResHandler);
                    } else {
                        req.endHandler(v -> cReq.send(cResHandler));
                        pump.start();
                    }
                } else {
                    loggingHandler.appendRequestPayload(bodyData);
                    // we already have the body complete in-memory - so we can use Content-Length header and avoid chunked transfer
                    cReq.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(bodyData.length()));
                    cReq.send(bodyData, cResHandler);
                }

                loggingHandler.request(cReq.headers());
            }
        });
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

    private void installExceptionHandler(final HttpServerRequest req, final String targetUri, final long startTime, HttpClientRequest cReq) {
        cReq.exceptionHandler(exception -> {
            monitoringHandler.stopRequestMetricTracking(rule.getMetricName(), startTime, req.uri());
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
                    error("Response already written. Not sure about the state. Closing server connection for stability reason", req, targetUri);
                    req.response().close();
                    return;
                }
                respondError(req, StatusCode.SERVICE_UNAVAILABLE);
            }
        });
    }

    private Handler<AsyncResult<HttpClientResponse>> getAsyncHttpClientResponseHandler(final HttpServerRequest req, final String targetUri, final Logger log, final Map<String, String> profileHeaderMap, final LoggingHandler loggingHandler, final long startTime) {
        return asyncResult -> {
            HttpClientResponse cRes = asyncResult.result();
            if (asyncResult.failed()) {
                error(asyncResult.cause().getMessage(), req, targetUri);
                req.response().setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code());
                req.response().setStatusMessage(asyncResult.cause().getMessage());
                req.response().end();
                return;
            }
            monitoringHandler.stopRequestMetricTracking(rule.getMetricName(), startTime, req.uri());
            loggingHandler.setResponse(cRes);
            req.response().setStatusCode(cRes.statusCode());
            req.response().setStatusMessage(cRes.statusMessage());

            int statusCode = cRes.statusCode();

            // translate with header info
            int translatedStatus = Translator.translateStatusCode(statusCode, req.headers());

            // nothing changed?
            if (statusCode == translatedStatus) {
                translatedStatus = Translator.translateStatusCode(statusCode, rule, log);
            }

            boolean translated = statusCode != translatedStatus;

            // set the statusCode (if nothing hapend, it will remain the same)
            statusCode = translatedStatus;

            req.response().setStatusCode(statusCode);

            if (translated) {
                req.response().setStatusMessage(HttpResponseStatus.valueOf(statusCode).reasonPhrase());
            }

            // Add received headers to original request but remove headers that should not get forwarded.
            MultiMap headersToForward = cRes.headers();
            headersToForward = HttpHeaderUtil.removeNonForwardHeaders(headersToForward);
            HttpHeaderUtil.mergeHeaders(req.response().headers(), headersToForward, targetUri);
            if (profileHeaderMap != null && !profileHeaderMap.isEmpty()) {
                HttpHeaderUtil.mergeHeaders(req.response().headers(), MultiMap.caseInsensitiveMultiMap().addAll(profileHeaderMap), targetUri);
            }
            // if we receive a chunked transfer then we also use chunked
            // otherwise, upstream must have sent a Content-Length - or no body at all (e.g. for "304 not modified" responses)
            if (req.response().headers().contains(HttpHeaders.TRANSFER_ENCODING, "chunked", true)) {
                req.response().setChunked(true);
            }

            final LoggingWriteStream loggingWriteStream = new LoggingWriteStream(req.response(), loggingHandler, false);
            final Pump pump = Pump.pump(cRes, loggingWriteStream);
            cRes.endHandler(v -> {
                try {
                    req.response().end();
                    ResponseStatusCodeLogUtil.debug(req, StatusCode.fromCode(req.response().getStatusCode()), Forwarder.class);
                } catch (IllegalStateException e) {
                    // ignore because maybe already closed
                }
                vertx.runOnContext(event -> loggingHandler.log());
            });
            pump.start();

            Runnable unpump = () -> {
                // disconnect the clientResponse from the Pump and resume this (probably paused-by-pump) stream to keep it alive
                pump.stop();
//                cRes.handler(buf -> {
//                    // drain to nothing
//                });
                cRes.resume(); // resume the (probably paused) stream
            };

            cRes.exceptionHandler(exception -> {
                LOG.warn("Failed to read upstream response for '{} {}'", req.method(), targetUri, exception);
                unpump.run();
                error("Problem with backend: " + exception.getMessage(), req, targetUri);
                respondError(req, StatusCode.INTERNAL_SERVER_ERROR);
            });
            req.connection().closeHandler((aVoid) -> unpump.run());
        };
    }

    private void error(String message, HttpServerRequest request, String uri) {
        RequestLoggerFactory.getLogger(Forwarder.class, request).error(rule.getScheme() + "://" + target + uri + " " + message);
    }
}

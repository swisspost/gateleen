package org.swisspush.gateleen.routing;

import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
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

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Forwards requests to the backend.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class Forwarder implements Handler<RoutingContext> {

    private String userProfilePath;
    private HttpClient client;
    private Pattern urlPattern;
    private String target;
    private Rule rule;
    private String base64UsernamePassword;
    private LoggingResourceManager loggingResourceManager;
    private MonitoringHandler monitoringHandler;
    private ResourceStorage storage;
    private Vertx vertx;

    private static final String ON_BEHALF_OF_HEADER = "x-on-behalf-of";
    private static final String USER_HEADER = "x-rp-usr";
    private static final String USER_HEADER_PREFIX = "x-user-";
    private static final String ETAG_HEADER = "Etag";
    private static final String IF_NONE_MATCH_HEADER = "if-none-match";
    private static final String SELF_REQUEST_HEADER = "x-self-request";

    private static final Logger LOG = LoggerFactory.getLogger(Forwarder.class);

    public Forwarder(Vertx vertx, HttpClient client, Rule rule, final ResourceStorage storage, LoggingResourceManager loggingResourceManager, MonitoringHandler monitoringHandler, String userProfilePath) {
        this.vertx = vertx;
        this.client = client;
        this.rule = rule;
        this.loggingResourceManager = loggingResourceManager;
        this.monitoringHandler = monitoringHandler;
        this.storage = storage;
        this.urlPattern = Pattern.compile(rule.getUrlPattern());
        this.target = rule.getHost() + ":" + rule.getPort();
        this.userProfilePath = userProfilePath;
        if (rule.getUsername() != null && !rule.getUsername().isEmpty()) {
            String password = rule.getPassword() == null ? null : rule.getPassword().trim();
            base64UsernamePassword = Base64.getEncoder().encodeToString((rule.getUsername().trim() + ":" + password).getBytes());
        }
    }

    private Map<String, String> createProfileHeaderValues(JsonObject profile, Logger log) {
        Map<String, String> profileValues = new HashMap<>();
        if (rule.getProfile() != null) {
            String[] ruleProfile = rule.getProfile();
            for (int i = 0; i < ruleProfile.length; i++) {
                String headerKey = ruleProfile[i];
                String headerValue = profile.getString(headerKey);
                if (headerKey != null && headerValue != null) {
                    profileValues.put(USER_HEADER_PREFIX + headerKey, headerValue);
                    log.debug("Sending header-information for key " + headerKey + ", value = " + headerValue);
                } else {
                    if (headerKey != null) {
                        log.debug("We should send profile information '" + headerKey + "' but this information was not found in profile.");
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
     * @param req - the original request
     * @param bodyData - a buffer with the body data, null if the request
     * was not yet consumed
     */
    public void handle(final HttpServerRequest req, final Buffer bodyData) {
        monitoringHandler.updateRequestsMeter(target, req.uri());
        monitoringHandler.updateRequestPerRuleMonitoring(req, rule.getMetricName());
        final String targetUri = urlPattern.matcher(req.uri()).replaceFirst(rule.getPath()).replaceAll("\\/\\/", "/");
        final Logger log = RequestLoggerFactory.getLogger(Forwarder.class, req);
        log.debug("Forwarding request: " + req.uri() + " to " + rule.getScheme() + "://" + target + targetUri + " with rule " + rule.getRuleIdentifier());
        final String userId = extractUserId(req, log);

        if (userId != null && rule.getProfile() != null && userProfilePath != null) {
            log.debug("Get profile information for user '" + userId + "' to append to headers");
            String userProfileKey = String.format(userProfilePath, userId);
            req.pause(); // pause the request to avoid problems with starting another async request (storage)
            storage.get(userProfileKey, buffer -> {
                Map<String, String> profileHeaderMap = new HashMap<>();
                if (buffer != null) {
                    JsonObject profile = new JsonObject(buffer.toString());
                    profileHeaderMap = createProfileHeaderValues(profile, log);
                    log.debug("Got profile information of user '" + userId + "'");
                    log.debug("Going to send parts of the profile in header: " + profileHeaderMap);
                } else {
                    log.debug("No profile information found in local storage for user '" + userId + "'");
                }
                handleRequest(req, bodyData, targetUri, log, profileHeaderMap);
            });
        } else {
            handleRequest(req, bodyData, targetUri, log, null);
        }
    }

    /**
     * Returns the userId defined in the on-behalf-of-header if provided, the userId from user-header otherwise.
     * 
     * @param request request
     * @param log log
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

    private void handleRequest(final HttpServerRequest req, final Buffer bodyData, final String targetUri, final Logger log, final Map<String, String> profileHeaderMap) {
        final LoggingHandler loggingHandler = new LoggingHandler(loggingResourceManager, req, vertx.eventBus());

        final String uniqueId = req.headers().get("x-rp-unique_id");
        final String timeout = req.headers().get("x-timeout");
        final long startTime = monitoringHandler.startRequestMetricTracking(rule.getMetricName(), req.uri());

        final HttpClientRequest cReq = prepareRequest(req, targetUri, log, profileHeaderMap, loggingHandler, startTime);

        if (timeout != null) {
            cReq.setTimeout(Long.valueOf(timeout));
        } else {
            cReq.setTimeout(rule.getTimeout());
        }

        // per https://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.10
        MultiMap headersToForward = req.headers();
        headersToForward = HttpHeaderUtil.removeNonForwardHeaders(headersToForward);
        cReq.headers().setAll(headersToForward);

        if (!ResponseStatusCodeLogUtil.isRequestToExternalTarget(target)) {
            cReq.headers().set(SELF_REQUEST_HEADER, "true");
        }

        if (uniqueId != null) {
            cReq.headers().set("x-rp-unique-id", uniqueId);
        }
        setProfileHeaders(log, profileHeaderMap, cReq);
        // https://jira/browse/NEMO-1494
        // the Host has to be set, if only added it will add a second value and not overwrite existing ones
        cReq.headers().set("Host", target.split("/")[0]);
        if (base64UsernamePassword != null) {
            cReq.headers().set("Authorization", "Basic " + base64UsernamePassword);
        }


        MultiMap headers = cReq.headers();
        final HeaderFunctions.EvalScope evalScope = rule.getHeaderFunction().apply(headers);
        if (evalScope.getErrorMessage() != null) {
            log.warn("Problem invoking Header functions: {}", evalScope.getErrorMessage());
            final HttpServerResponse response = req.response();
            response.setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
            response.setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
            response.end(evalScope.getErrorMessage());
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
            WriteStream<Buffer> cReqWrapped = new WriteStream<Buffer>() {
                private boolean firstBuffer = true;

                @Override
                public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
                    cReq.exceptionHandler(handler);
                    return this;
                }

                @Override
                public WriteStream<Buffer> write(Buffer data) {
                    // only now we know for sure that there IS a body.
                    if (firstBuffer) {
                        // avoid multiple calls due to a 'syncronized' block in HttpClient's implementation
                        firstBuffer = false;
                        cReq.setChunked(true);
                    }
                    cReq.write(data);
                    return this;
                }

                @Override
                public void end() {
                    cReq.end();
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


            final LoggingWriteStream loggingWriteStream = new LoggingWriteStream(cReqWrapped, loggingHandler, true);
            final Pump pump = Pump.pump(req, loggingWriteStream);
            req.endHandler(v -> cReq.end());
            req.exceptionHandler(t -> {
                RequestLoggerFactory
                        .getLogger(Forwarder.class, req)
                        .warn("Exception during forwarding - closing (forwarding) client connection", t);
                cReq.connection().close();
            });
            pump.start();
        } else {
            loggingHandler.appendRequestPayload(bodyData);
            // we already have the body complete in-memory - so we can use Content-Length header and avoid chunked transfer
            cReq.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(bodyData.length()));
            cReq.end(bodyData);
        }

        loggingHandler.request(cReq.headers());

        req.resume();
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
                req.response().setStatusCode(StatusCode.TIMEOUT.getStatusCode());
                req.response().setStatusMessage(StatusCode.TIMEOUT.getStatusMessage());
                try {
                    ResponseStatusCodeLogUtil.info(req, StatusCode.TIMEOUT, Forwarder.class);
                    req.response().end(req.response().getStatusMessage());
                } catch (IllegalStateException e) {
                    // ignore because maybe already closed
                }
            } else {
                error(exception.getMessage(), req, targetUri);
                if (req.response().ended() || req.response().headWritten()) {
                    error("Response already written. Not sure about the state. Closing server connection for stability reason", req, targetUri);
                    req.response().close();
                    return;
                }

                req.response().setStatusCode(StatusCode.SERVICE_UNAVAILABLE.getStatusCode());
                req.response().setStatusMessage(StatusCode.SERVICE_UNAVAILABLE.getStatusMessage());
                try {
                    ResponseStatusCodeLogUtil.info(req, StatusCode.SERVICE_UNAVAILABLE, Forwarder.class);
                    req.response().end(req.response().getStatusMessage());
                } catch (IllegalStateException e) {
                    // ignore because maybe already closed
                }
            }
        });
    }

    private HttpClientRequest prepareRequest(final HttpServerRequest req, final String targetUri, final Logger log, final Map<String, String> profileHeaderMap, final LoggingHandler loggingHandler, final long startTime) {
        return client.request(req.method(), targetUri, cRes -> {
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

            // set the statusCode (if nothing hapend, it will remain the same)
            statusCode = translatedStatus;

            req.response().setStatusCode(statusCode);

            // Add received headers to original request but remove headers that should not get forwarded.
            MultiMap headersToForward = cRes.headers();
            headersToForward = HttpHeaderUtil.removeNonForwardHeaders(headersToForward);
            req.response().headers().addAll(headersToForward);

            if (profileHeaderMap != null && !profileHeaderMap.isEmpty()) {
                req.response().headers().addAll(profileHeaderMap);
            }
            // if we receive a chunked transfer then we also use chunked
            // otherwise, upstream must have sent a Content-Length - or no body at all (e.g. for "304 not modified" responses)
            if ("chunked".equals(req.response().headers().get(HttpHeaders.TRANSFER_ENCODING))) {
//            if (!req.response().headers().contains("Content-Length")) {
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

            cRes.exceptionHandler(exception -> {
                cRes.resume();
                error("Problem with backend: " + exception.getMessage(), req, targetUri);
                req.response().setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
                req.response().setStatusMessage(StatusCode.INTERNAL_SERVER_ERROR.getStatusMessage());
                try {
                    ResponseStatusCodeLogUtil.info(req, StatusCode.INTERNAL_SERVER_ERROR, Forwarder.class);
                    req.response().end(req.response().getStatusMessage());
                } catch (IllegalStateException e) {
                    // ignore because maybe already closed
                }
            });
            req.connection().closeHandler(closed -> {
                cRes.resume();
            });
        });
    }

    private void error(String message, HttpServerRequest request, String uri) {
        RequestLoggerFactory.getLogger(Forwarder.class, request).error(rule.getScheme() + "://" + target + uri + " " + message);
    }
}

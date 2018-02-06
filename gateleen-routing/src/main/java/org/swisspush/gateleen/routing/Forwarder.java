package org.swisspush.gateleen.routing;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.Pump;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.storage.ResourceStorage;
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

        // Fix unique ID header name for backends not able to handle underscore in header names.
        cReq.headers().setAll(req.headers());
        // per https://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.10
        cReq.headers().getAll("connection").stream().forEach(cReq.headers()::remove);
        cReq.headers().remove("connection");

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

        setStaticHeaders(cReq);

        cReq.setChunked(true);

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
            final LoggingWriteStream loggingWriteStream = new LoggingWriteStream(cReq, loggingHandler, true);
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
            cReq.end(bodyData);
        }

        loggingHandler.request(cReq.headers());

        req.resume();
    }

    private void setStaticHeaders(HttpClientRequest cReq) {
        if (rule.getStaticHeaders() != null) {
            for (Map.Entry<String, String> entry : rule.getStaticHeaders().entrySet()) {
                String entryValue = entry.getValue();
                if (entryValue != null && entryValue.length() > 0 ) {
                    cReq.headers().set(entry.getKey(), entry.getValue());
                } else {
                    cReq.headers().remove(entry.getKey());
                }
            }
        }
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

            req.response().headers().addAll(cRes.headers());
            if (profileHeaderMap != null && !profileHeaderMap.isEmpty()) {
                req.response().headers().addAll(profileHeaderMap);
            }
            if (!req.response().headers().contains("Content-Length")) {
                req.response().setChunked(true);
            }

            final LoggingWriteStream loggingWriteStream = new LoggingWriteStream(req.response(), loggingHandler, false);
            final Pump pump = Pump.pump(cRes, loggingWriteStream);
            cRes.endHandler(v -> {
                try {
                    pump.stop();
                    req.response().end();
                    ResponseStatusCodeLogUtil.debug(req, StatusCode.fromCode(req.response().getStatusCode()), Forwarder.class);
                } catch (IllegalStateException e) {
                    // ignore because maybe already closed
                }
                vertx.runOnContext(event -> loggingHandler.log());
            });
            pump.start();

            cRes.exceptionHandler(exception -> {
                pump.stop();
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
        });
    }

    private void error(String message, HttpServerRequest request, String uri) {
        RequestLoggerFactory.getLogger(Forwarder.class, request).error(rule.getScheme() + "://" + target + uri + " " + message);
    }
}

package org.swisspush.gateleen.routing.routing;

import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.logging.LoggingHandler;
import org.swisspush.gateleen.core.logging.LoggingResourceManager;
import org.swisspush.gateleen.core.monitoring.MonitoringHandler;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.core.util.StringUtils;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(Forwarder.class);
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

    private Map<String, String> createProfileHeaderValues(JsonObject profile) {
        Map<String, String> profileValues = new HashMap<>();
        if (rule.getProfile() != null) {
            String[] ruleProfile = rule.getProfile();
            for (int i = 0; i < ruleProfile.length; i++) {
                String headerKey = ruleProfile[i];
                String headerValue = profile.getString(headerKey);
                if (headerKey != null && headerValue != null) {
                    profileValues.put(USER_HEADER_PREFIX + headerKey, headerValue);
                    LOG.debug("Sending header-information for key " + headerKey + ", value = " + headerValue);
                } else {
                    if (headerKey != null) {
                        LOG.debug("We should send profile information '" + headerKey + "' but this information was not found in profile.");
                    } else {
                        LOG.debug("We should send profile information but header key is null.");
                    }
                }
            }
        } else {
            LOG.debug("rule.profile is null, this rule will not send profile information.");
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
        final String targetUri = urlPattern.matcher(req.uri()).replaceAll(rule.getPath()).replaceAll("\\/\\/", "/");
        final Logger log = RequestLoggerFactory.getLogger(Forwarder.class, req);
        log.debug("Forwarding request: " + req.uri() + " to " + rule.getScheme() + "://" + target + targetUri);

        final String userId = extractUserId(req, log);

        if (userId != null && rule.getProfile() != null && userProfilePath != null) {
            log.debug("Get profile information for user '" + userId + "' to append to headers");
            String userProfileKey = String.format(userProfilePath, userId);
            req.pause(); // pause the request to avoid problems with starting another async request (storage)
            storage.get(userProfileKey, buffer -> {
                Map<String, String> profileHeaderMap = new HashMap<>();
                if (buffer != null) {
                    JsonObject profile = new JsonObject(buffer.toString());
                    profileHeaderMap = createProfileHeaderValues(profile);
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
        final LoggingHandler loggingHandler = new LoggingHandler(loggingResourceManager, req);

        final String uniqueId = req.headers().get("x-rp-unique_id");
        final long startTime = monitoringHandler.startRequestMetricTracking(rule.getMetricName(), req.uri());

        final HttpClientRequest cReq = prepareRequest(req, targetUri, log, profileHeaderMap, loggingHandler, startTime);

        cReq.setTimeout(rule.getTimeout());
        // Fix unique ID header name for backends not able to handle underscore in header names.
        cReq.headers().setAll(req.headers());

        if (!ResponseStatusCodeLogUtil.isRequestToExternalTarget(target)) {
            cReq.headers().set(SELF_REQUEST_HEADER, "");
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

        if (rule.getTimeout() > 0) {
            cReq.setTimeout(rule.getTimeout());
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
            req.handler(data -> {
                cReq.write(data);
                loggingHandler.appendRequestPayload(data);
            });
            req.endHandler(v -> cReq.end());
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
                cReq.headers().set(entry.getKey(), entry.getValue());
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
                    ResponseStatusCodeLogUtil.debug(req, StatusCode.TIMEOUT, Forwarder.class);
                    req.response().end(req.response().getStatusMessage());
                } catch (IllegalStateException e) {
                    // ignore because maybe already closed
                }
            } else {
                error(exception.getMessage(), req, targetUri);
                req.response().setStatusCode(StatusCode.SERVICE_UNAVAILABLE.getStatusCode());
                req.response().setStatusMessage(StatusCode.SERVICE_UNAVAILABLE.getStatusMessage());
                try {
                    ResponseStatusCodeLogUtil.debug(req, StatusCode.SERVICE_UNAVAILABLE, Forwarder.class);
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
            if (req.response().getStatusCode() == StatusCode.NOT_MODIFIED.getStatusCode()) {
                req.response().headers().add("Content-Length", "0");
            }
            if (!req.response().headers().contains("Content-Length")) {
                req.response().setChunked(true);
            }

            final String responseEtag = cRes.headers().get(ETAG_HEADER);
            if (responseEtag != null && !responseEtag.isEmpty()) {
                cRes.bodyHandler(data -> {
                    String ifNoneMatchHeader = req.headers().get(IF_NONE_MATCH_HEADER);
                    if (responseEtag.equals(ifNoneMatchHeader)) {
                        req.response().setStatusCode(StatusCode.NOT_MODIFIED.getStatusCode());
                        req.response().setStatusMessage(StatusCode.NOT_MODIFIED.getStatusMessage());
                        ResponseStatusCodeLogUtil.debug(req, StatusCode.NOT_MODIFIED, Forwarder.class);
                        req.response().end();
                    } else {
                        ResponseStatusCodeLogUtil.debug(req, StatusCode.fromCode(req.response().getStatusCode()), Forwarder.class);
                        req.response().end(data);
                    }
                    loggingHandler.appendResponsePayload(data);
                    vertx.runOnContext(event -> loggingHandler.log());
                });
            } else {
                cRes.handler(data -> {
                    req.response().write(data);
                    loggingHandler.appendResponsePayload(data);
                });
                cRes.endHandler(v -> {
                    try {
                        req.response().end();
                        ResponseStatusCodeLogUtil.debug(req, StatusCode.fromCode(req.response().getStatusCode()), Forwarder.class);
                    } catch (IllegalStateException e) {
                        // ignore because maybe already closed
                    }
                    vertx.runOnContext(event -> loggingHandler.log());
                });
            }

            cRes.exceptionHandler(exception -> {
                error("Problem with backend: " + exception.getMessage(), req, targetUri);
                req.response().setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
                req.response().setStatusMessage(StatusCode.INTERNAL_SERVER_ERROR.getStatusMessage());
                try {
                    ResponseStatusCodeLogUtil.debug(req, StatusCode.INTERNAL_SERVER_ERROR, Forwarder.class);
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

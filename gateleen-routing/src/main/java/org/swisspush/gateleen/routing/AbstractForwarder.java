package org.swisspush.gateleen.routing;

import javax.annotation.Nullable;

import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.util.HttpHeaderUtil;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.logging.LogAppenderRepository;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.monitoring.MonitoringHandler;

import java.util.UUID;

public abstract class AbstractForwarder implements Handler<RoutingContext> {

    protected final Rule rule;
    protected final LoggingResourceManager loggingResourceManager;
    protected final LogAppenderRepository logAppenderRepository;
    protected final MonitoringHandler monitoringHandler;
    protected final String metricNameTag;

    public static final String FORWARDS_METRIC_NAME = "gateleen.forwarded.seconds";
    public static final String FORWARDS_METRIC_DESCRIPTION = "Durations of forwarded requests";
    public static final String FORWARDER_METRIC_TAG_TYPE = "type";
    public static final String FORWARDER_METRIC_TAG_METRICNAME = "metricName";
    public static final String FORWARDER_NO_METRICNAME = "no-metric-name";

    public AbstractForwarder(Rule rule, LoggingResourceManager loggingResourceManager, LogAppenderRepository logAppenderRepository, @Nullable MonitoringHandler monitoringHandler) {
        this.rule = rule;
        this.loggingResourceManager = loggingResourceManager;
        this.logAppenderRepository = logAppenderRepository;
        this.monitoringHandler = monitoringHandler;

        this.metricNameTag = rule.getMetricName() != null ? rule.getMetricName() : FORWARDER_NO_METRICNAME;
    }

    protected abstract void setMeterRegistry(MeterRegistry meterRegistry);

    protected boolean doHeadersFilterMatch(final HttpServerRequest request) {
        final Logger log = RequestLoggerFactory.getLogger(getClass(), request);

        if (rule.getHeadersFilterPattern() != null) {
            log.debug("Looking for request headers with pattern {}", rule.getHeadersFilterPattern().pattern());
            boolean matchFound = HttpHeaderUtil.hasMatchingHeader(request.headers(), rule.getHeadersFilterPattern());
            if (matchFound) {
                log.debug("Matching request headers found");
            } else {
                log.debug("No matching request headers found. Looking for the next routing rule");
            }
            return matchFound;
        }

        return false;
    }

    protected void respondError(HttpServerRequest req, StatusCode statusCode) {
        respondError(req, statusCode, null);
    }

    /**
     * Responds with the given error status. If an {@code errorId} is given, it is appended to the response body
     * and logged together with the request uri, so the client-visible error can be found in the logs.
     */
    protected void respondError(HttpServerRequest req, StatusCode statusCode, @Nullable String errorId) {
        Logger log = null;
        try {
            ResponseStatusCodeLogUtil.info(req, statusCode, getClass());

            String msg = statusCode.getStatusMessage();
            var rsp = req.response();
            if (rsp.headWritten()) {
                log = (log != null) ? log : RequestLoggerFactory.getLogger(AbstractForwarder.class, req);
                log.warn("Response already sent. Cannot send: HTTP {} {}", statusCode, msg);
            } else {
                if (errorId != null) {
                    log = (log != null) ? log : RequestLoggerFactory.getLogger(AbstractForwarder.class, req);
                    log.warn("[{}] Responding HTTP {} {} for {} {}", errorId, statusCode.getStatusCode(), msg, req.method(), req.uri());
                }
                rsp.setStatusCode(statusCode.getStatusCode());
                rsp.setStatusMessage(msg);
                rsp.end(errorId != null ? withErrorId(msg, errorId) : msg);
            }
        } catch (IllegalStateException ex) {
            // (nearly) ignore because underlying connection maybe already closed
            log = (log != null) ? log : RequestLoggerFactory.getLogger(AbstractForwarder.class, req);
            log.warn("IllegalStateException while sending error response for {}", req.uri(), ex);
        }
    }

    /**
     * Creates a random id to correlate the error text sent to the client with the matching server log entry.
     */
    protected static String newErrorId() {
        return "ERR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    protected static String withErrorId(String message, String errorId) {
        return message + " (errorId: " + errorId + ")";
    }

    protected String getRequestTarget(String target) {
        if (target != null && (target.contains("localhost") || target.contains("127.0.0.1"))) {
            return "local";
        }
        return "external";
    }
}

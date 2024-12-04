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

public abstract class AbstractForwarder implements Handler<RoutingContext> {

    protected final Rule rule;
    protected final LoggingResourceManager loggingResourceManager;
    protected final LogAppenderRepository logAppenderRepository;
    protected final MonitoringHandler monitoringHandler;
    protected final String metricNameTag;

    public static final String FORWARDER_METRIC_NAME = "gateleen.forwarded.requests";
    public static final String FORWARDER_METRIC_DESCRIPTION = "gateleen.forwarded.requests";
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

        if(rule.getHeadersFilterPattern() != null){
            log.debug("Looking for request headers with pattern {}", rule.getHeadersFilterPattern().pattern());
            boolean matchFound = HttpHeaderUtil.hasMatchingHeader(request.headers(), rule.getHeadersFilterPattern());
            if(matchFound) {
                log.debug("Matching request headers found");
            } else {
                log.debug("No matching request headers found. Looking for the next routing rule");
            }
            return matchFound;
        }

        return false;
    }

    protected void respondError(HttpServerRequest req, StatusCode statusCode) {
        Logger log = null;
        try {
            ResponseStatusCodeLogUtil.info(req, statusCode, getClass());

            String msg = statusCode.getStatusMessage();
            var rsp = req.response();
            if (rsp.headWritten()) {
                log = (log != null) ? log : RequestLoggerFactory.getLogger(AbstractForwarder.class, req);
                log.warn("Response already sent. Cannot send: HTTP {} {}", statusCode, msg);
            } else {
                rsp.setStatusCode(statusCode.getStatusCode());
                rsp.setStatusMessage(msg);
                rsp.end(msg);
            }
        } catch (IllegalStateException ex) {
            // (nearly) ignore because underlying connection maybe already closed
            log = (log != null) ? log : RequestLoggerFactory.getLogger(AbstractForwarder.class, req);
            log.warn("IllegalStateException while sending error response for {}", req.uri(), ex);
        }
    }

    protected boolean isRequestToExternalTarget(String target) {
        boolean isInternalRequest = false;
        if (target != null) {
            isInternalRequest = target.contains("localhost") || target.contains("127.0.0.1");
        }
        return !isInternalRequest;
    }
}

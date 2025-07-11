package org.swisspush.gateleen.routing;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpServerResponse;

import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.http.HeaderFunctions;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.logging.LogAppenderRepository;
import org.swisspush.gateleen.logging.LoggingHandler;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.monitoring.MonitoringHandler;

import javax.annotation.Nullable;

/**
 * Consumes requests without forwarding them anywhere.
 *
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class NullForwarder extends AbstractForwarder {

    private EventBus eventBus;
    private MeterRegistry meterRegistry;
    private Timer forwardTimer;

    public NullForwarder(Rule rule, LoggingResourceManager loggingResourceManager, LogAppenderRepository logAppenderRepository, @Nullable MonitoringHandler monitoringHandler, EventBus eventBus) {
        super(rule, loggingResourceManager, logAppenderRepository, monitoringHandler);
        this.eventBus = eventBus;
    }

    /**
     * Sets the MeterRegistry for this NullForwarder.
     * If the provided MeterRegistry is not null, it initializes the forwardCounter
     * with the appropriate metric name, description, and tags.
     *
     * @param meterRegistry the MeterRegistry to set
     */
    @Override
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        if(meterRegistry != null) {
            forwardTimer = Timer.builder(FORWARDS_METRIC_NAME)
                    .description(FORWARDS_METRIC_DESCRIPTION)
                    .publishPercentiles(0.75, 0.95)
                    .tag(FORWARDER_METRIC_TAG_METRICNAME, metricNameTag)
                    .tag(FORWARDER_METRIC_TAG_TYPE, "null")
                    .register(meterRegistry);
        }
    }

    @Override
    public void handle(final RoutingContext ctx) {
        final Logger log = RequestLoggerFactory.getLogger(NullForwarder.class, ctx.request());

        if (rule.hasHeadersFilterPattern() && !doHeadersFilterMatch(ctx.request())) {
            ctx.next();
            return;
        }

        Timer.Sample timerSample = null;
        if(meterRegistry != null) {
            timerSample = Timer.start(meterRegistry);
            timerSample.stop(forwardTimer);
        }

        if(monitoringHandler != null) {
            monitoringHandler.updateRequestPerRuleMonitoring(ctx.request(), rule.getMetricName());
        }
        final LoggingHandler loggingHandler = new LoggingHandler(loggingResourceManager, logAppenderRepository, ctx.request(), eventBus);
        log.debug("Not forwarding request: {} with rule {}", ctx.request().uri(), rule.getRuleIdentifier());
        final HeadersMultiMap requestHeaders = new HeadersMultiMap();
        requestHeaders.addAll(ctx.request().headers());

        // probably useless, as the request is discarded anyway
        // but we write the headers also to the request log - so juest to be complete:
        final HeaderFunctions.EvalScope evalScope = rule.getHeaderFunction().apply(requestHeaders);// Apply the header manipulation chain
        if (evalScope.getErrorMessage() != null) {
            log.warn("Problem invoking Header functions: {}", evalScope.getErrorMessage());
            final HttpServerResponse response = ctx.request().response();
            response.setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
            response.setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
            response.end(evalScope.getErrorMessage());
            return;
        }

        final Buffer header = Buffer.buffer(new HttpRequest(ctx.request().method(), ctx.request().uri(), requestHeaders, null).toJsonObject().encode());
        final Buffer requestBuffer = Buffer.buffer();
        requestBuffer.setInt(0, header.length()).appendBuffer(header);

        int statusCode = StatusCode.OK.getStatusCode();
        String statusMessage = StatusCode.OK.getStatusMessage();

        /*
         * We create a response for the client,
         * but we discard the request and therefore
         * do not forward it.
         */
        ctx.response().setStatusCode(statusCode);
        ctx.response().setStatusMessage(statusMessage);
        ctx.response().headers().add("Content-Length", "0");

        ctx.request().handler(buffer -> {
            loggingHandler.appendRequestPayload(buffer, requestHeaders);
            requestBuffer.appendBuffer(buffer);
        });

        ctx.request().endHandler(event -> {
            MultiMap responseHeaders = ctx.response().headers();
            loggingHandler.log(ctx.request().uri(), ctx.request().method(), statusCode, statusMessage, requestHeaders, responseHeaders != null ? responseHeaders : new HeadersMultiMap());
            ctx.response().end();
        });
    }
}

package org.swisspush.gateleen.routing;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.headers.VertxHttpHeaders;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.http.HeaderFunctions;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.logging.LoggingHandler;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.monitoring.MonitoringHandler;

/**
 * Consumes requests without forwarding them anywhere.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class NullForwarder implements Handler<RoutingContext> {

    private LoggingResourceManager loggingResourceManager;
    private MonitoringHandler monitoringHandler;
    private Rule rule;
    private EventBus eventBus;

    public NullForwarder(Rule rule, LoggingResourceManager loggingResourceManager, MonitoringHandler monitoringHandler, EventBus eventBus){
        this.rule = rule;
        this.loggingResourceManager = loggingResourceManager;
        this.monitoringHandler = monitoringHandler;
        this.eventBus = eventBus;
    }

    @Override
    public void handle(final RoutingContext ctx) {
        monitoringHandler.updateRequestPerRuleMonitoring(ctx.request(), rule.getMetricName());
        final LoggingHandler loggingHandler = new LoggingHandler(loggingResourceManager, ctx.request(), eventBus);
        final Logger log = RequestLoggerFactory.getLogger(NullForwarder.class, ctx.request());
        log.debug("Not forwarding request: {} with rule {}", ctx.request().uri(), rule.getRuleIdentifier());
        final VertxHttpHeaders requestHeaders = new VertxHttpHeaders();
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
            MultiMap responseHeaders = ctx.response().headers();
            loggingHandler.log(ctx.request().uri(), ctx.request().method(), statusCode, statusMessage, requestHeaders, responseHeaders != null ? responseHeaders : new VertxHttpHeaders());
        });

        ctx.response().end();
    }
}

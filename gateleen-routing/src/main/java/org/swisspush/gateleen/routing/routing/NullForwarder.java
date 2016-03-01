package org.swisspush.gateleen.routing.routing;

import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.logging.logging.LoggingHandler;
import org.swisspush.gateleen.logging.logging.LoggingResourceManager;
import org.swisspush.gateleen.core.util.StatusCode;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Consumes requests without forwarding them anywhere.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class NullForwarder implements Handler<RoutingContext> {

    private LoggingResourceManager loggingResourceManager;
    private Rule rule;

    public NullForwarder(Rule rule, LoggingResourceManager loggingResourceManager){
        this.rule = rule;
        this.loggingResourceManager = loggingResourceManager;
    }

    @Override
    public void handle(final RoutingContext ctx) {
        final LoggingHandler loggingHandler = new LoggingHandler(loggingResourceManager, ctx.request());
        final Logger log = RequestLoggerFactory.getLogger(NullForwarder.class, ctx.request());
        log.debug("Not forwarding request: " + ctx.request().uri());
        final MultiMap requestHeaders = new CaseInsensitiveHeaders();
        requestHeaders.addAll(ctx.request().headers());
        if (rule.getStaticHeaders() != null) {
            for (Map.Entry<String, String> entry : rule.getStaticHeaders().entrySet()) {
                requestHeaders.set(entry.getKey(), entry.getValue());
            }
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
            loggingHandler.log(ctx.request().uri(), ctx.request().method(), statusCode, statusMessage, requestHeaders, responseHeaders != null ? responseHeaders : new CaseInsensitiveHeaders());
        });

        ctx.response().end();
    }
}

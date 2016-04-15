package org.swisspush.gateleen.routing;

import org.swisspush.gateleen.core.cors.CORSHandler;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.json.JsonMultiMap;
import org.swisspush.gateleen.logging.LoggingHandler;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Forwards to storage through the event bus, bypassing the network layer.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class StorageForwarder implements Handler<RoutingContext> {

    private EventBus eventBus;
    private Pattern urlPattern;
    private Rule rule;
    private String address;
    private LoggingResourceManager loggingResourceManager;
    private MonitoringHandler monitoringHandler;
    private CORSHandler corsHandler;

    public StorageForwarder(EventBus eventBus, Rule rule, LoggingResourceManager loggingResourceManager, MonitoringHandler monitoringHandler) {
        this.eventBus = eventBus;
        this.rule = rule;
        this.loggingResourceManager = loggingResourceManager;
        this.monitoringHandler = monitoringHandler;
        this.address = Address.storageAddress() + "-" + rule.getStorage();
        urlPattern = Pattern.compile(rule.getUrlPattern());
        corsHandler = new CORSHandler();
    }

    @Override
    public void handle(final RoutingContext ctx) {
        final LoggingHandler loggingHandler = new LoggingHandler(loggingResourceManager, ctx.request());
        final String targetUri = urlPattern.matcher(ctx.request().uri()).replaceAll(rule.getPath()).replaceAll("\\/\\/", "/");
        final Logger log = RequestLoggerFactory.getLogger(StorageForwarder.class, ctx.request());
        monitoringHandler.updateRequestsMeter("localhost", ctx.request().uri());
        monitoringHandler.updateRequestPerRuleMonitoring(ctx.request(), rule.getMetricName());
        final long startTime = monitoringHandler.startRequestMetricTracking(rule.getMetricName(), ctx.request().uri());
        log.debug("Forwarding request: " + ctx.request().uri() + " to storage " + rule.getStorage() + " " + targetUri + " with rule " + rule.getRuleIdentifier());
        final MultiMap requestHeaders = new CaseInsensitiveHeaders();
        requestHeaders.addAll(ctx.request().headers());
        if (rule.getStaticHeaders() != null) {
            for (Map.Entry<String, String> entry : rule.getStaticHeaders().entrySet()) {
                requestHeaders.set(entry.getKey(), entry.getValue());
            }
        }

        setUniqueIdHeader(requestHeaders);

        final Buffer header = Buffer.buffer(new HttpRequest(ctx.request().method(), targetUri, requestHeaders, null).toJsonObject().encode());
        final Buffer requestBuffer = Buffer.buffer();
        requestBuffer.setInt(0, header.length()).appendBuffer(header);
        ctx.request().handler(buffer -> {
            loggingHandler.appendRequestPayload(buffer, requestHeaders);
            requestBuffer.appendBuffer(buffer);
        });
        ctx.request().endHandler(event -> eventBus.send(address, requestBuffer, new DeliveryOptions().setSendTimeout(1000), new Handler<AsyncResult<Message<Buffer>>>() {
            @Override
            public void handle(AsyncResult<Message<Buffer>> result) {
                HttpServerResponse response = ctx.response();
                monitoringHandler.stopRequestMetricTracking(rule.getMetricName(), startTime, ctx.request().uri());
                if (result.failed()) {
                    String statusMessage = "Storage request for "+ctx.request().uri()+" failed with message: " + result.cause().getMessage();
                    response.setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
                    response.setStatusMessage(statusMessage);
                    response.end();
                    log.error("Storage request failed", result.cause());
                } else {
                    Buffer buffer = result.result().body();
                    int headerLength = buffer.getInt(0);
                    JsonObject responseJson = new JsonObject(buffer.getString(4, headerLength + 4));
                    JsonArray headers = responseJson.getJsonArray("headers");
                    MultiMap responseHeaders = null;
                    if (headers != null && headers.size() > 0) {
                        responseHeaders = JsonMultiMap.fromJson(headers);

                        setUniqueIdHeader(responseHeaders);

                        ctx.response().headers().setAll(responseHeaders);
                    }
                    corsHandler.handle(ctx.request());
                    int statusCode = responseJson.getInteger("statusCode");

                    // translate with header info
                    int translatedStatus = Translator.translateStatusCode(statusCode, ctx.request().headers());

                    // nothing changed?
                    if (statusCode == translatedStatus) {
                        translatedStatus = Translator.translateStatusCode(statusCode, rule, log);
                    }

                    // set the statusCode (if nothing hapend, it will remain the same)
                    statusCode = translatedStatus;

                    response.setStatusCode(statusCode);
                    String statusMessage = responseJson.getString("statusMessage");
                    if (statusMessage != null) {
                        response.setStatusMessage(statusMessage);
                    }
                    Buffer data = buffer.getBuffer(4 + headerLength, buffer.length());
                    response.headers().set("content-length", "" + data.length());
                    response.write(data);
                    response.end();
                    ResponseStatusCodeLogUtil.debug(ctx.request(), StatusCode.fromCode(statusCode), StorageForwarder.class);
                    if (responseHeaders != null) {
                        loggingHandler.appendResponsePayload(data, responseHeaders);
                    }
                    loggingHandler.log(ctx.request().uri(), ctx.request().method(), statusCode, statusMessage, requestHeaders, responseHeaders != null ? responseHeaders : new CaseInsensitiveHeaders());
                }
            }
        }));
    }

    /**
     * Translates the x-rp-unique_id header to x-rp-unique-id.
     * 
     * @param headers
     */
    private void setUniqueIdHeader(MultiMap headers) {
        final String uid = headers.get("x-rp-unique_id");
        if (uid != null) {
            headers.set("x-rp-unique-id", uid);
        }
    }
}

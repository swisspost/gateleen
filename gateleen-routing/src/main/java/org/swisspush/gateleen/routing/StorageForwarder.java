package org.swisspush.gateleen.routing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.cors.CORSHandler;
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;
import org.swisspush.gateleen.core.http.HeaderFunctions;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.json.JsonMultiMap;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.ExpiryCheckHandler;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.logging.LogAppenderRepository;
import org.swisspush.gateleen.logging.LoggingHandler;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.monitoring.MonitoringHandler;

import javax.annotation.Nullable;
import java.util.regex.Pattern;

/**
 * Forwards to storage through the event bus, bypassing the network layer.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class StorageForwarder extends AbstractForwarder {

    private EventBus eventBus;
    private Pattern urlPattern;
    private String address;
    private CORSHandler corsHandler;
    private GateleenExceptionFactory gateleenExceptionFactory;

    private Timer forwardTimer;
    private Counter storageWriteNoExpiry;
    private Counter storageWriteWithExpiry;
    private MeterRegistry meterRegistry;

    private static final String TYPE_STORAGE = "storage";
    public static final String STORAGE_WRITES_METRIC_NAME = "gateleen.forwarded.storage.writes";
    public static final String STORAGE_WRITES_METRIC_DESCRIPTION = "Amount of storage write operations";
    private static final String METRIC_TAG_EXPIRES = "expires";

    public StorageForwarder(EventBus eventBus, Rule rule, LoggingResourceManager loggingResourceManager,
                            LogAppenderRepository logAppenderRepository, @Nullable MonitoringHandler monitoringHandler,
                            GateleenExceptionFactory gateleenExceptionFactory) {
        super(rule, loggingResourceManager, logAppenderRepository, monitoringHandler);
        this.eventBus = eventBus;
        this.address = Address.storageAddress() + "-" + rule.getStorage();
        urlPattern = Pattern.compile(rule.getUrlPattern());
        corsHandler = new CORSHandler();
        this.gateleenExceptionFactory = gateleenExceptionFactory;
    }

    /**
     * Sets the MeterRegistry for this StorageForwarder.
     * If the provided MeterRegistry is not null, it initializes the forwardCounter
     * with the appropriate metric name, description, and tags.
     *
     * @param meterRegistry the MeterRegistry to set
     */
    @Override
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        if (meterRegistry != null) {
            forwardTimer = Timer.builder(FORWARDS_METRIC_NAME)
                    .description(FORWARDS_METRIC_DESCRIPTION)
                    .publishPercentiles(0.75, 0.95)
                    .tag(FORWARDER_METRIC_TAG_METRICNAME, metricNameTag)
                    .tag(FORWARDER_METRIC_TAG_TYPE, TYPE_STORAGE)
                    .register(meterRegistry);
            storageWriteNoExpiry = Counter.builder(STORAGE_WRITES_METRIC_NAME)
                    .description(STORAGE_WRITES_METRIC_DESCRIPTION)
                    .tag(FORWARDER_METRIC_TAG_METRICNAME, metricNameTag)
                    .tag(METRIC_TAG_EXPIRES, "false")
                    .register(meterRegistry);
            storageWriteWithExpiry = Counter.builder(STORAGE_WRITES_METRIC_NAME)
                    .description(STORAGE_WRITES_METRIC_DESCRIPTION)
                    .tag(FORWARDER_METRIC_TAG_METRICNAME, metricNameTag)
                    .tag(METRIC_TAG_EXPIRES, "true")
                    .register(meterRegistry);
        }
    }

    @Override
    public void handle(final RoutingContext ctx) {
        final LoggingHandler loggingHandler = new LoggingHandler(loggingResourceManager, logAppenderRepository, ctx.request(), this.eventBus);
        final String targetUri = urlPattern.matcher(ctx.request().uri()).replaceAll(rule.getPath()).replaceAll("\\/\\/", "/");
        final Logger log = RequestLoggerFactory.getLogger(StorageForwarder.class, ctx.request());

        if (rule.hasHeadersFilterPattern() && !doHeadersFilterMatch(ctx.request())) {
            ctx.next();
            return;
        }

        Long startTime = null;

        Timer.Sample timerSample = null;
        if (meterRegistry != null) {
            timerSample = Timer.start(meterRegistry);
        }

        if (monitoringHandler != null) {
            monitoringHandler.updateRequestsMeter("localhost", ctx.request().uri());
            monitoringHandler.updateRequestPerRuleMonitoring(ctx.request(), rule.getMetricName());
            startTime = monitoringHandler.startRequestMetricTracking(rule.getMetricName(), ctx.request().uri());
        }
        log.debug("Forwarding {} request: {} to storage {} {} with rule {}", ctx.request().method(), ctx.request().uri(),
                rule.getStorage(), targetUri, rule.getRuleIdentifier());
        final HeadersMultiMap requestHeaders = new HeadersMultiMap();
        requestHeaders.addAll(ctx.request().headers());

        final HeaderFunctions.EvalScope evalScope = rule.getHeaderFunction().apply(requestHeaders);// Apply the header manipulation chain
        if (evalScope.getErrorMessage() != null) {
            log.warn("Problem invoking Header functions: {}", evalScope.getErrorMessage());
            final HttpServerResponse response = ctx.request().response();
            response.setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
            response.setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
            response.end(evalScope.getErrorMessage());
            return;
        }
        handleStorageWriteMetrics(ctx.request().method(), requestHeaders);

        final Buffer header = Buffer.buffer(new HttpRequest(ctx.request().method(), targetUri, requestHeaders, null).toJsonObject().encode());
        final Buffer requestBuffer = Buffer.buffer();
        requestBuffer.setInt(0, header.length()).appendBuffer(header);
        ctx.request().handler(buffer -> {
            loggingHandler.appendRequestPayload(buffer, requestHeaders);
            requestBuffer.appendBuffer(buffer);
        });
        Long finalStartTime = startTime;

        Timer.Sample finalTimerSample = timerSample;

        ctx.request().endHandler(event ->
                eventBus.request(address, requestBuffer, new DeliveryOptions().setSendTimeout(10000),
                        (Handler<AsyncResult<Message<Buffer>>>) result -> {
                            HttpServerResponse response = ctx.response();
                            if (monitoringHandler != null) {
                                monitoringHandler.stopRequestMetricTracking(rule.getMetricName(), finalStartTime, ctx.request().uri());
                            }

                            if (finalTimerSample != null) {
                                finalTimerSample.stop(forwardTimer);
                            }

                            if (result.failed()) {
                                String statusMessage = "Storage request for " + ctx.request().uri() + " failed with message: " + result.cause().getMessage();
                                response.setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
                                response.setStatusMessage(statusMessage);
                                response.end();
                                log.error("{}", statusMessage, gateleenExceptionFactory.newException(result.cause()));
                            } else {
                                Buffer buffer = result.result().body();
                                int headerLength = buffer.getInt(0);
                                JsonObject responseJson = new JsonObject(buffer.getString(4, headerLength + 4));
                                JsonArray headers = responseJson.getJsonArray("headers");
                                MultiMap responseHeaders = null;
                                if (headers != null && !headers.isEmpty()) {
                                    responseHeaders = JsonMultiMap.fromJson(headers);

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

                                boolean translated = statusCode != translatedStatus;

                                // set the statusCode (if nothing hapend, it will remain the same)
                                statusCode = translatedStatus;

                                response.setStatusCode(statusCode);
                                String statusMessage;
                                if (translated) {
                                    statusMessage = HttpResponseStatus.valueOf(statusCode).reasonPhrase();
                                    response.setStatusMessage(statusMessage);
                                } else {
                                    statusMessage = responseJson.getString("statusMessage");
                                    if (statusMessage != null) {
                                        response.setStatusMessage(statusMessage);
                                    }
                                }
                                Buffer data = buffer.getBuffer(4 + headerLength, buffer.length());
                                response.headers().set("content-length", "" + data.length());
                                response.write(data);
                                response.end();
                                ResponseStatusCodeLogUtil.debug(ctx.request(), StatusCode.fromCode(statusCode), StorageForwarder.class);
                                if (responseHeaders != null) {
                                    loggingHandler.appendResponsePayload(data, responseHeaders);
                                }
                                loggingHandler.log(ctx.request().uri(), ctx.request().method(), statusCode, statusMessage,
                                        requestHeaders, responseHeaders != null ? responseHeaders : new HeadersMultiMap());
                            }

                        }));
    }

    private void handleStorageWriteMetrics(HttpMethod method, MultiMap headers) {
        if (method == HttpMethod.PUT) {
            Integer expiry = ExpiryCheckHandler.getExpireAfter(headers);
            incrementStorageWrite(expiry != null);
        }
    }

    private void incrementStorageWrite(boolean withExpiry) {
        if (withExpiry && storageWriteWithExpiry != null) {
            storageWriteWithExpiry.increment();
        } else if (!withExpiry && storageWriteNoExpiry != null) {
            storageWriteNoExpiry.increment();
        }
    }
}

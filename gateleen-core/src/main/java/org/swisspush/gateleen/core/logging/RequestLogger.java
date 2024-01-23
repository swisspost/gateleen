package org.swisspush.gateleen.core.logging;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.JsonObjectUtils;

/**
 * Logger class to manually log requests. This class is mainly used for requests to managed configuration
 * resources which are handled separately.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class RequestLogger {

    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final String MESSAGE = "message";
    public static final String STATUS = "status";
    public static final String REQUEST_URI = "request_uri";
    public static final String REQUEST_METHOD = "request_method";
    public static final String REQUEST_HEADERS = "request_headers";
    public static final String RESPONSE_HEADERS = "response_headers";
    public static final String REQUEST_STATUS = "request_status";
    public static final String BODY = "body";

    private RequestLogger() {
    }

    public static void logRequest(EventBus eventBus, final HttpServerRequest request, final int status, Buffer data) {
        logRequest(eventBus, request, status, data, request.response().headers());
    }

    public static void logRequest(EventBus eventBus, final HttpServerRequest request, final int status, Buffer data, final MultiMap responseHeaders) {
        Logger log = RequestLoggerFactory.getLogger(RequestLogger.class, request);
        log.info("Notify logging to eventually log the payload and headers of request to uri {}", request.uri());
        JsonObject logEntry = new JsonObject();
        logEntry.put(REQUEST_URI, request.uri());
        logEntry.put(REQUEST_METHOD, request.method().name());
        logEntry.put(REQUEST_HEADERS, JsonObjectUtils.multiMapToJsonObject(request.headers()));
        logEntry.put(RESPONSE_HEADERS, JsonObjectUtils.multiMapToJsonObject(responseHeaders));
        logEntry.put(REQUEST_STATUS, status);
        logEntry.put(BODY, data.toString());

        eventBus.request(Address.requestLoggingConsumerAddress(), logEntry, (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
            if (reply.failed()) {
                log.warn("Failed to log the payload and headers. Cause: {}", reply.cause().getMessage());
            } else if (ERROR.equals(reply.result().body().getString("status"))) {
                log.warn("Failed to log the payload and headers. Cause: {}", reply.result().body().getString("message"));
            }
        });
    }
}

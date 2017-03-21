package org.swisspush.gateleen.logging;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.StatusCode;

import java.util.Map;

/**
 * Created by webermarca on 21.03.2017.
 */
public class RequestLoggingConsumer {
    private final Vertx vertx;
    private final LoggingResourceManager loggingResourceManager;

    public RequestLoggingConsumer(Vertx vertx, LoggingResourceManager loggingResourceManager) {
        this.vertx = vertx;
        this.loggingResourceManager = loggingResourceManager;

        vertx.eventBus().localConsumer(Address.requestLoggingConsumerAddress(), (Handler<Message<JsonObject>>) event -> {
            JsonObject body = event.body();

            String uri = body.getString("request_uri");
            String method = body.getString("request_method");
            HttpMethod httpMethod = HttpMethod.valueOf(method);
            JsonObject requestHeaders = body.getJsonObject("request_headers");
            JsonObject responseHeaders = body.getJsonObject("response_headers");

            MultiMap requestHeadersMap = getHeadersFromJsonObject(requestHeaders);
            MultiMap responseHeadersMap = getHeadersFromJsonObject(responseHeaders);

            Integer status = body.getInteger("status");
            JsonObject payload = body.getJsonObject("payload");
            Buffer payloadBuffer = Buffer.buffer(payload.encode());

            RequestLoggingRequest req = new RequestLoggingRequest(uri, httpMethod, requestHeadersMap);
            logRequest(req, status, payloadBuffer, responseHeadersMap);
        });
    }

    private MultiMap getHeadersFromJsonObject(JsonObject headers){
        CaseInsensitiveHeaders headersMap = new CaseInsensitiveHeaders();
        for (Map.Entry<String, Object> stringObjectEntry : headers.getMap().entrySet()) {
            headersMap.add(stringObjectEntry.getKey(), (String) stringObjectEntry.getValue());
        }
        return headersMap;
    }

    /**
     * Logs the provided request using the {@link LoggingHandler}. The decision whether to log the request or not is still
     * based on the logging configuration resource.
     *
     * @param request the request to log
     * @param status the http status code to log
     * @param data the payload of the request to log
     * @param responseHeaders the response headers
     */
    public void logRequest(final HttpServerRequest request, final int status, Buffer data, final MultiMap responseHeaders) {
        final LoggingHandler loggingHandler = new LoggingHandler(loggingResourceManager, request, vertx.eventBus());
        if (HttpMethod.PUT == request.method() || HttpMethod.POST == request.method()) {
            loggingHandler.appendRequestPayload(data);
        } else if (HttpMethod.GET == request.method()) {
            loggingHandler.appendResponsePayload(data, responseHeaders);
        }
        StatusCode statusCode = StatusCode.fromCode(status);
        final String statusMessage = (statusCode != null ? statusCode.getStatusMessage() : "");
        vertx.runOnContext(event -> loggingHandler.log(request.uri(), request.method(), status, statusMessage, request.headers(), responseHeaders));
    }

    class RequestLoggingRequest extends DummyHttpServerRequest {

        private String uri;
        private HttpMethod method;
        private MultiMap headers;

        public RequestLoggingRequest(String uri, HttpMethod method, MultiMap headers) {
            this.uri = uri;
            this.method = method;
            this.headers = headers;
        }

        @Override public HttpMethod method() {
            return method;
        }
        @Override public String uri() {
            return uri;
        }
        @Override public MultiMap headers() { return headers; }
    }
}

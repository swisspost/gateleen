package org.swisspush.gateleen.kafka;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;

public class StreamingRequest extends DummyHttpServerRequest {

    private final String uri;
    private final HttpMethod method;
    private final String body;
    private final MultiMap headers;
    private final HttpServerResponse response;

    StreamingRequest(HttpMethod method, String uri) {
        this(method, uri, "", new CaseInsensitiveHeaders(), new StreamingResponse());
    }

    StreamingRequest(HttpMethod method, String uri, String body, MultiMap headers, HttpServerResponse response) {
        this.method = method;
        this.uri = uri;
        this.body = body;
        this.headers = headers;
        this.response = response;
    }

    @Override public HttpMethod method() {
        return method;
    }
    @Override public String uri() {
        return uri;
    }
    @Override public MultiMap headers() { return headers; }

    @Override
    public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
        bodyHandler.handle(Buffer.buffer(body));
        return this;
    }

    @Override public HttpServerResponse response() { return response; }
}

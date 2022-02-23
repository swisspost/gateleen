package org.swisspush.gateleen.core.http;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.headers.HeadersMultiMap;

/**
 * Dummy class implementing {@link HttpServerResponse}. Override this class for your needs.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class DummyHttpServerResponse implements FastFailHttpServerResponse {
    private int statusCode;
    private String statusMessage;
    private String resultBuffer;

    private HeadersMultiMap headers = new HeadersMultiMap();

    public String getResultBuffer() {
        return resultBuffer;
    }

    @Override
    public int getStatusCode() {
        return statusCode;
    }

    @Override
    public HttpServerResponse setStatusCode(int statusCode) {
        this.statusCode = statusCode;
        return this;
    }

    @Override
    public String getStatusMessage() {
        return statusMessage;
    }

    @Override
    public HttpServerResponse setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
        return this;
    }

    @Override
    public MultiMap headers() {
        return headers;
    }

    @Override
    public HttpServerResponse putHeader(String name, String value) {
        headers.add(name, value);
        return this;
    }

    @Override
    public HttpServerResponse putHeader(CharSequence name, CharSequence value) {
        headers.add(name, value);
        return this;
    }

    @Override
    public Future<Void> end(String chunk) {
        this.resultBuffer = chunk;
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> end(Buffer chunk) {
        this.resultBuffer = chunk.toString();
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> end() {
        return Future.succeededFuture();
    }

    @Override
    public boolean ended() {
        return false;
    }

    @Override
    public boolean closed() {
        return false;
    }
}

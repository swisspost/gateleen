package org.swisspush.gateleen.core.http;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.headers.VertxHttpHeaders;

/**
 * Dummy class implementing {@link HttpServerResponse}. Override this class for your needs.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class DummyHttpServerResponse implements FastFailHttpServerResponse {
    private int statusCode;
    private String statusMessage;
    private String resultBuffer;

    private VertxHttpHeaders headers = new VertxHttpHeaders();

    public String getResultBuffer(){
        return resultBuffer;
    }

    @Override public int getStatusCode() {
        return statusCode;
    }

    @Override public HttpServerResponse setStatusCode(int statusCode) {
        this.statusCode = statusCode;
        return this;
    }

    @Override public String getStatusMessage() {
        return statusMessage;
    }

    @Override public HttpServerResponse setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
        return this;
    }

    @Override public MultiMap headers() {
        return headers;
    }

    @Override public HttpServerResponse putHeader(String name, String value) {
        headers.add(name, value);
        return this;
    }

    @Override public HttpServerResponse putHeader(CharSequence name, CharSequence value) {
        headers.add(name, value);
        return this;
    }

    @Override public void end(String chunk) {
        this.resultBuffer = chunk;
    }

    @Override public void end(Buffer chunk) {
        this.resultBuffer = chunk.toString();
    }

    @Override public void end() {}

    @Override public boolean ended() { return false; }

}

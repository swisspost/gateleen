package org.swisspush.gateleen.core.http;

import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.net.NetSocket;

import java.util.List;

public interface FastFaiHttpClientResponse extends HttpClientResponse {

    String msg = "Do override this method to mock expected behaviour.";

    default HttpClientResponse fetch(long amount) {
        throw new UnsupportedOperationException(msg);

    }

    default HttpClientResponse resume() {
        throw new UnsupportedOperationException(msg);

    }

    default HttpClientResponse exceptionHandler(Handler<Throwable> handler) {
        throw new UnsupportedOperationException(msg);

    }

    default HttpClientResponse handler(Handler<Buffer> handler) {
        throw new UnsupportedOperationException(msg);

    }

    default HttpClientResponse pause() {
        throw new UnsupportedOperationException(msg);

    }

    default HttpClientResponse endHandler(Handler<Void> endHandler) {
        throw new UnsupportedOperationException(msg);

    }

    default HttpVersion version() {
        throw new UnsupportedOperationException(msg);

    }

    default int statusCode() {
        throw new UnsupportedOperationException(msg);
    }

    default String statusMessage() {
        throw new UnsupportedOperationException(msg);

    }

    default MultiMap headers() {
        throw new UnsupportedOperationException(msg);

    }

    default @Nullable String getHeader(String headerName) {
        throw new UnsupportedOperationException(msg);

    }

    default String getHeader(CharSequence headerName) {
        throw new UnsupportedOperationException(msg);

    }

    default @Nullable String getTrailer(String trailerName) {
        throw new UnsupportedOperationException(msg);

    }

    default MultiMap trailers() {
        throw new UnsupportedOperationException(msg);

    }

    default List<String> cookies() {
        throw new UnsupportedOperationException(msg);

    }

    default HttpClientResponse bodyHandler(Handler<Buffer> bodyHandler) {
        throw new UnsupportedOperationException(msg);

    }

    default HttpClientResponse customFrameHandler(Handler<HttpFrame> handler) {
        throw new UnsupportedOperationException(msg);

    }

    default NetSocket netSocket() {
        throw new UnsupportedOperationException(msg);

    }

    default HttpClientRequest request() {
        throw new UnsupportedOperationException(msg);

    }

    default HttpClientResponse streamPriorityHandler(Handler<StreamPriority> handler) {
        throw new UnsupportedOperationException(msg);

    }
}

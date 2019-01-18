package org.swisspush.gateleen.core.http;

import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;

public interface FastFailHttpClientRequest extends HttpClientRequest {

    String msg = "Do override this method to mock expected behaviour.";

    default HttpClientRequest exceptionHandler(Handler<Throwable> handler) {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest write(Buffer data) {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest setWriteQueueMaxSize(int maxSize) {
        throw new UnsupportedOperationException(msg);
    }

    default boolean writeQueueFull() {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest drainHandler(Handler<Void> handler) {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest handler(Handler<HttpClientResponse> handler) {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest pause() {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest resume() {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest fetch(long amount) {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest endHandler(Handler<Void> endHandler) {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest setFollowRedirects(boolean followRedirects) {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest setChunked(boolean chunked) {
        throw new UnsupportedOperationException(msg);
    }

    default boolean isChunked() {
        throw new UnsupportedOperationException(msg);
    }

    default HttpMethod method() {
        throw new UnsupportedOperationException(msg);
    }

    default String getRawMethod() {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest setRawMethod(String method) {
        throw new UnsupportedOperationException(msg);
    }

    default String absoluteURI() {
        throw new UnsupportedOperationException(msg);
    }

    default String uri() {
        throw new UnsupportedOperationException(msg);
    }

    default String path() {
        throw new UnsupportedOperationException(msg);
    }

    default String query() {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest setHost(String host) {
        throw new UnsupportedOperationException(msg);
    }

    default String getHost() {
        throw new UnsupportedOperationException(msg);
    }

    default MultiMap headers() {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest putHeader(String name, String value) {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest putHeader(CharSequence name, CharSequence value) {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest putHeader(String name, Iterable<String> values) {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest putHeader(CharSequence name, Iterable<CharSequence> values) {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest write(String chunk) {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest write(String chunk, String enc) {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest continueHandler(@Nullable Handler<Void> handler) {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest sendHead() {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest sendHead(Handler<HttpVersion> completionHandler) {
        throw new UnsupportedOperationException(msg);
    }

    default void end(String chunk) {
        throw new UnsupportedOperationException(msg);
    }

    default void end(String chunk, String enc) {
        throw new UnsupportedOperationException(msg);
    }

    default void end(Buffer chunk) {
        throw new UnsupportedOperationException(msg);
    }

    default void end() {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest setTimeout(long timeoutMs) {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest pushHandler(Handler<HttpClientRequest> handler) {
        throw new UnsupportedOperationException(msg);
    }

    default boolean reset(long code) {
        throw new UnsupportedOperationException(msg);
    }

    default HttpConnection connection() {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest connectionHandler(@Nullable Handler<HttpConnection> handler) {
        throw new UnsupportedOperationException(msg);
    }

    default HttpClientRequest writeCustomFrame(int type, int flags, Buffer payload) {
        throw new UnsupportedOperationException(msg);
    }

    default StreamPriority getStreamPriority() {
        throw new UnsupportedOperationException(msg);
    }
}

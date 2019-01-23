package org.swisspush.gateleen.core.http;

import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;


/**
 *
 * A {@link HttpServerRequest} throwing an exception no matter which method got
 * called.
 *
 * This is useful for testing. For testing inherit from this and override the
 * methods you need to mock.
 *
 */
public interface FastFailHttpServerResponse extends HttpServerResponse {

    String msg = "Do override this method to mock expected behaviour.";

    default HttpServerResponse exceptionHandler(Handler<Throwable> handler) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse write(Buffer data) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse setWriteQueueMaxSize(int maxSize) {
        throw new UnsupportedOperationException( msg );
    }

    default boolean writeQueueFull() {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse drainHandler(Handler<Void> handler) {
        throw new UnsupportedOperationException( msg );
    }

    default int getStatusCode() {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse setStatusCode(int statusCode) {
        throw new UnsupportedOperationException( msg );
    }

    default String getStatusMessage() {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse setStatusMessage(String statusMessage) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse setChunked(boolean chunked) {
        throw new UnsupportedOperationException( msg );
    }

    default boolean isChunked() {
        throw new UnsupportedOperationException( msg );
    }

    default MultiMap headers() {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse putHeader(String name, String value) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse putHeader(CharSequence name, CharSequence value) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse putHeader(String name, Iterable<String> values) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse putHeader(CharSequence name, Iterable<CharSequence> values) {
        throw new UnsupportedOperationException( msg );
    }

    default MultiMap trailers() {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse putTrailer(String name, String value) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse putTrailer(CharSequence name, CharSequence value) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse putTrailer(String name, Iterable<String> values) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse putTrailer(CharSequence name, Iterable<CharSequence> value) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse closeHandler(@Nullable Handler<Void> handler) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse endHandler(@Nullable Handler<Void> handler) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse write(String chunk, String enc) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse write(String chunk) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse writeContinue() {
        throw new UnsupportedOperationException( msg );
    }

    default void end(String chunk) {
        throw new UnsupportedOperationException( msg );
    }

    default void end(String chunk, String enc) {
        throw new UnsupportedOperationException( msg );
    }

    default void end(Buffer chunk) {
        throw new UnsupportedOperationException( msg );
    }

    default void end() {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse sendFile(String filename, long offset, long length) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse sendFile(String filename, long offset, long length, Handler<AsyncResult<Void>> resultHandler) {
        throw new UnsupportedOperationException( msg );
    }

    default void close() {
        throw new UnsupportedOperationException( msg );
    }

    default boolean ended() {
        throw new UnsupportedOperationException( msg );
    }

    default boolean closed() {
        throw new UnsupportedOperationException( msg );
    }

    default boolean headWritten() {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse headersEndHandler(@Nullable Handler<Void> handler) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse bodyEndHandler(@Nullable Handler<Void> handler) {
        throw new UnsupportedOperationException( msg );
    }

    default long bytesWritten() {
        throw new UnsupportedOperationException( msg );
    }

    default int streamId() {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse push(HttpMethod method, String host, String path, Handler<AsyncResult<HttpServerResponse>> handler) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse push(HttpMethod method, String path, MultiMap headers, Handler<AsyncResult<HttpServerResponse>> handler) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse push(HttpMethod method, String path, Handler<AsyncResult<HttpServerResponse>> handler) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse push(HttpMethod method, String host, String path, MultiMap headers, Handler<AsyncResult<HttpServerResponse>> handler) {
        throw new UnsupportedOperationException( msg );
    }

    default void reset(long code) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse writeCustomFrame(int type, int flags, Buffer payload) {
        throw new UnsupportedOperationException( msg );
    }
}

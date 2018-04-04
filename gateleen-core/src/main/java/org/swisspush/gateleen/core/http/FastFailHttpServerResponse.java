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
public class FastFailHttpServerResponse implements HttpServerResponse {

    private static final String msg = "Do override this method to mock expected behaviour.";

    @Override
    public HttpServerResponse exceptionHandler(Handler<Throwable> handler) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse write(Buffer data) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse setWriteQueueMaxSize(int maxSize) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public boolean writeQueueFull() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse drainHandler(Handler<Void> handler) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public int getStatusCode() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse setStatusCode(int statusCode) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public String getStatusMessage() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse setStatusMessage(String statusMessage) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse setChunked(boolean chunked) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public boolean isChunked() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public MultiMap headers() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse putHeader(String name, String value) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse putHeader(CharSequence name, CharSequence value) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse putHeader(String name, Iterable<String> values) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse putHeader(CharSequence name, Iterable<CharSequence> values) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public MultiMap trailers() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse putTrailer(String name, String value) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse putTrailer(CharSequence name, CharSequence value) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse putTrailer(String name, Iterable<String> values) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse putTrailer(CharSequence name, Iterable<CharSequence> value) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse closeHandler(@Nullable Handler<Void> handler) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse endHandler(@Nullable Handler<Void> handler) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse write(String chunk, String enc) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse write(String chunk) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse writeContinue() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public void end(String chunk) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public void end(String chunk, String enc) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public void end(Buffer chunk) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public void end() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse sendFile(String filename, long offset, long length) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse sendFile(String filename, long offset, long length, Handler<AsyncResult<Void>> resultHandler) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public boolean ended() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public boolean closed() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public boolean headWritten() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse headersEndHandler(@Nullable Handler<Void> handler) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse bodyEndHandler(@Nullable Handler<Void> handler) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public long bytesWritten() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public int streamId() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse push(HttpMethod method, String host, String path, Handler<AsyncResult<HttpServerResponse>> handler) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse push(HttpMethod method, String path, MultiMap headers, Handler<AsyncResult<HttpServerResponse>> handler) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse push(HttpMethod method, String path, Handler<AsyncResult<HttpServerResponse>> handler) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse push(HttpMethod method, String host, String path, MultiMap headers, Handler<AsyncResult<HttpServerResponse>> handler) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public void reset(long code) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse writeCustomFrame(int type, int flags, Buffer payload) {
        throw new UnsupportedOperationException( msg );
    }
}

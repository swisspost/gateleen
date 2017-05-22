package org.swisspush.gateleen.core.http;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;

/**
 * Dummy class implementing {@link HttpServerResponse}. Override this class for your needs.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class DummyHttpServerResponse implements HttpServerResponse {
    private int statusCode;
    private String statusMessage;
    private String resultBuffer;

    private MultiMap headers = new CaseInsensitiveHeaders();

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

    @Override public HttpServerResponse setChunked(boolean chunked) {
        throw new UnsupportedOperationException();
    }

    @Override public boolean isChunked() {
        throw new UnsupportedOperationException();
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

    @Override public HttpServerResponse putHeader(String name, Iterable<String> values) {
        throw new UnsupportedOperationException();
    }

    @Override public HttpServerResponse putHeader(CharSequence name, Iterable<CharSequence> values) {
        throw new UnsupportedOperationException();
    }

    @Override public MultiMap trailers() {
        throw new UnsupportedOperationException();
    }

    @Override public HttpServerResponse putTrailer(String name, String value) {
        throw new UnsupportedOperationException();
    }

    @Override public HttpServerResponse putTrailer(CharSequence name, CharSequence value) {
        throw new UnsupportedOperationException();
    }

    @Override public HttpServerResponse putTrailer(String name, Iterable<String> values) {
        throw new UnsupportedOperationException();
    }

    @Override public HttpServerResponse putTrailer(CharSequence name, Iterable<CharSequence> value) {
        throw new UnsupportedOperationException();
    }

    @Override public HttpServerResponse closeHandler(Handler<Void> handler) {
        throw new UnsupportedOperationException();
    }

    @Override public HttpServerResponse write(Buffer chunk) {
        throw new UnsupportedOperationException();
    }

    @Override public HttpServerResponse write(String chunk, String enc) {
        throw new UnsupportedOperationException();
    }

    @Override public HttpServerResponse write(String chunk) {
        throw new UnsupportedOperationException();
    }

    @Override public HttpServerResponse writeContinue() { throw new UnsupportedOperationException(); }

    @Override public void end(String chunk) {
        this.resultBuffer = chunk;
    }

    @Override public void end(String chunk, String enc) {
        throw new UnsupportedOperationException();
    }

    @Override public void end(Buffer chunk) {
        this.resultBuffer = chunk.toString();
    }

    @Override public void end() {}

    @Override public HttpServerResponse sendFile(String filename) {
        throw new UnsupportedOperationException();
    }

    @Override public HttpServerResponse sendFile(String filename, long offset, long length) {
        throw new UnsupportedOperationException();
    }

    @Override public HttpServerResponse sendFile(String filename, Handler<AsyncResult<Void>> resultHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse sendFile(String filename, long offset, long length, Handler<AsyncResult<Void>> resultHandler) {
        throw new UnsupportedOperationException();
    }

    @Override public void close() {
        throw new UnsupportedOperationException();
    }

    @Override public boolean ended() { return false; }

    @Override public boolean closed() { throw new UnsupportedOperationException(); }

    @Override public boolean headWritten() { throw new UnsupportedOperationException(); }

    @Override public HttpServerResponse headersEndHandler(Handler<Void> handler) { throw new UnsupportedOperationException(); }

    @Override public HttpServerResponse bodyEndHandler(Handler<Void> handler) { throw new UnsupportedOperationException(); }

    @Override public long bytesWritten() { throw new UnsupportedOperationException(); }

    @Override
    public int streamId() { throw new UnsupportedOperationException(); }

    @Override
    public HttpServerResponse push(HttpMethod method, String host, String path, Handler<AsyncResult<HttpServerResponse>> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse push(HttpMethod method, String path, MultiMap headers, Handler<AsyncResult<HttpServerResponse>> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse push(HttpMethod method, String path, Handler<AsyncResult<HttpServerResponse>> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse push(HttpMethod method, String host, String path, MultiMap headers, Handler<AsyncResult<HttpServerResponse>> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void reset(long code) { throw new UnsupportedOperationException(); }

    @Override
    public HttpServerResponse writeCustomFrame(int type, int flags, Buffer payload) {
        throw new UnsupportedOperationException();
    }

    @Override public HttpServerResponse setWriteQueueMaxSize(int maxSize) {
        throw new UnsupportedOperationException();
    }

    @Override public boolean writeQueueFull() {
        throw new UnsupportedOperationException();
    }

    @Override public HttpServerResponse drainHandler(Handler<Void> handler) {
        throw new UnsupportedOperationException();
    }

    @Override public HttpServerResponse exceptionHandler(Handler<Throwable> handler) {
        throw new UnsupportedOperationException();
    }

}

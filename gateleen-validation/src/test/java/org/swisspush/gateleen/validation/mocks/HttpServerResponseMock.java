package org.swisspush.gateleen.validation.mocks;

import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;

import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpFrame;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.HostAndPort;

import java.util.Set;

/**
 * Mock for the HttpServerResponse class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class HttpServerResponseMock implements HttpServerResponse {
    private int statusCode;
    private String statusMessage;

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
    public HttpServerResponse setChunked(boolean chunked) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isChunked() {
        return false;
    }

    @Override
    public MultiMap headers() {
        return MultiMap.caseInsensitiveMultiMap();
    }

    @Override
    public HttpServerResponse putHeader(String name, String value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse putHeader(CharSequence name, CharSequence value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse putHeader(String name, Iterable<String> values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse putHeader(CharSequence name, Iterable<CharSequence> values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public MultiMap trailers() {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse putTrailer(String name, String value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse putTrailer(CharSequence name, CharSequence value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse putTrailer(String name, Iterable<String> values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse putTrailer(CharSequence name, Iterable<CharSequence> value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse closeHandler(Handler<Void> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse endHandler(@Nullable Handler<Void> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Future<Void> write(String chunk, String enc) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void write(String chunk, String enc, Handler<AsyncResult<Void>> handler) {

    }

    @Override
    public Future<Void> write(String chunk) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void write(String chunk, Handler<AsyncResult<Void>> handler) {

    }

    @Override
    public HttpServerResponse writeContinue() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Future<Void> writeEarlyHints(MultiMap headers) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeEarlyHints(MultiMap headers, Handler<AsyncResult<Void>> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Future<Void> end(String chunk) {
        return Future.succeededFuture();
    }

    @Override
    public void end(String chunk, Handler<AsyncResult<Void>> handler) {

    }

    @Override
    public Future<Void> end(String chunk, String enc) {
        return Future.succeededFuture();
    }

    @Override
    public void end(String chunk, String enc, Handler<AsyncResult<Void>> handler) {

    }

    @Override
    public Future<Void> end(Buffer chunk) {
        return Future.succeededFuture();
    }

    @Override
    public void end(Buffer chunk, Handler<AsyncResult<Void>> handler) {

    }

    @Override
    public Future<Void> end() {
        return Future.succeededFuture();
    }

    @Override
    public void end(Handler<AsyncResult<Void>> handler) {

    }

    @Override
    public Future<Void> sendFile(String filename, long offset, long length) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse sendFile(String filename, Handler<AsyncResult<Void>> resultHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse sendFile(String filename, long offset, Handler<AsyncResult<Void>> resultHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse sendFile(String filename, long offset, long length, Handler<AsyncResult<Void>> resultHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {

    }

    @Override
    public boolean ended() {
        return false;
    }

    @Override
    public boolean closed() {
        return false;
    }

    @Override
    public boolean headWritten() {
        return false;
    }

    @Override
    public HttpServerResponse headersEndHandler(Handler<Void> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse bodyEndHandler(Handler<Void> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long bytesWritten() {
        return 0;
    }

    @Override
    public int streamId() {
        return 0;
    }

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
    public Future<HttpServerResponse> push(HttpMethod method, HostAndPort authority, String path, MultiMap headers) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Future<HttpServerResponse> push(HttpMethod method, String host, String path, MultiMap headers) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean reset(long code) {
        return false;
    }

    @Override
    public HttpServerResponse writeCustomFrame(int type, int flags, Buffer payload) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse writeCustomFrame(HttpFrame frame) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse addCookie(Cookie cookie) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable Cookie removeCookie(String name, boolean invalidate) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Cookie> removeCookies(String name, boolean invalidate) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable Cookie removeCookie(String name, String domain, String path, boolean invalidate) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse setWriteQueueMaxSize(int maxSize) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean writeQueueFull() {
        return false;
    }

    @Override
    public HttpServerResponse drainHandler(Handler<Void> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse exceptionHandler(Handler<Throwable> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Future<Void> write(Buffer data) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void write(Buffer data, Handler<AsyncResult<Void>> handler) {

    }

}
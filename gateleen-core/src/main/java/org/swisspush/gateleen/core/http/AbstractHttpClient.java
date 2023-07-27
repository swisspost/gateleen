package org.swisspush.gateleen.core.http;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.*;
import org.swisspush.gateleen.core.event.SucceededAsyncResult;

import java.util.List;
import java.util.function.Function;

/**
 * Base class with empty method implementations.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public abstract class AbstractHttpClient implements HttpClient {

    private final Vertx vertx;

    public AbstractHttpClient(Vertx vertx) {
        this.vertx = vertx;
    }

    protected abstract HttpClientRequest doRequest(HttpMethod method, String uri);

    public HttpClientRequest options(String uri) {
        return doRequest(HttpMethod.OPTIONS, uri);
    }

    public HttpClientRequest get(String uri) {
        return doRequest(HttpMethod.GET, uri);
    }

    public HttpClientRequest head(String uri) {
        return doRequest(HttpMethod.HEAD, uri);
    }

    public HttpClientRequest post(String uri) {
        return doRequest(HttpMethod.POST, uri);
    }

    @Override
    public HttpClient connectionHandler(Handler<HttpConnection> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient redirectHandler(Function<HttpClientResponse, Future<RequestOptions>> function) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Function<HttpClientResponse, Future<RequestOptions>> redirectHandler() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close(Handler<AsyncResult<Void>> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Future<Void> close() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void request(RequestOptions requestOptions, Handler<AsyncResult<HttpClientRequest>> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Future<HttpClientRequest> request(RequestOptions requestOptions) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void request(HttpMethod httpMethod, int i, String s, String s1, Handler<AsyncResult<HttpClientRequest>> handler) {
        Future.succeededFuture(doRequest(httpMethod, s1)).onComplete(handler);
    }

    @Override
    public Future<HttpClientRequest> request(HttpMethod httpMethod, int i, String s, String s1) {
        return Future.succeededFuture(doRequest(HttpMethod.GET, s1));
    }

    @Override
    public void request(HttpMethod httpMethod, String s, String s1, Handler<AsyncResult<HttpClientRequest>> handler) {
    }

    @Override
    public Future<HttpClientRequest> request(HttpMethod httpMethod, String s, String s1) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void request(HttpMethod method, String requestURI, Handler<AsyncResult<HttpClientRequest>> handler) {
        vertx.runOnContext(v -> handler.handle(new SucceededAsyncResult<>(doRequest(method, requestURI))));
    }

    @Override
    public Future<HttpClientRequest> request(HttpMethod httpMethod, String requestURI) {
        return Future.succeededFuture(doRequest(httpMethod, requestURI));
    }

    @Override
    public void webSocket(int i, String s, String s1, Handler<AsyncResult<WebSocket>> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Future<WebSocket> webSocket(int i, String s, String s1) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void webSocket(String s, String s1, Handler<AsyncResult<WebSocket>> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Future<WebSocket> webSocket(String s, String s1) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void webSocket(String s, Handler<AsyncResult<WebSocket>> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Future<WebSocket> webSocket(String s) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void webSocket(WebSocketConnectOptions webSocketConnectOptions, Handler<AsyncResult<WebSocket>> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Future<WebSocket> webSocket(WebSocketConnectOptions webSocketConnectOptions) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void webSocketAbs(String s, MultiMap multiMap, WebsocketVersion websocketVersion, List<String> list, Handler<AsyncResult<WebSocket>> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Future<WebSocket> webSocketAbs(String s, MultiMap multiMap, WebsocketVersion websocketVersion, List<String> list) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isMetricsEnabled() {
        throw new UnsupportedOperationException();
    }
}
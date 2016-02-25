package org.swisspush.gateleen.core.http;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.http.*;

/**
 * Base class with empty method implementations.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public abstract class AbstractHttpClient implements HttpClient {

    protected abstract HttpClientRequest doRequest(HttpMethod method, String uri, Handler<HttpClientResponse> responseHandler);

    @Override
    public HttpClient getNow(String uri, Handler<HttpClientResponse> responseHandler) {
        getNow(uri, null, responseHandler);
        return this;
    }

    @Override
    public HttpClientRequest post(int port, String host, String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest post(String host, String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest post(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest post(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest post(String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest options(String uri, Handler<HttpClientResponse> responseHandler) {
        return doRequest(HttpMethod.OPTIONS, uri, responseHandler);
    }

    @Override
    public HttpClientRequest optionsAbs(String absoluteURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest optionsAbs(String absoluteURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient optionsNow(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient optionsNow(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient optionsNow(String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest put(int port, String host, String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest put(String host, String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest put(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest put(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest put(String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest request(HttpMethod method, int port, String host, String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest request(HttpMethod method, String host, String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest request(HttpMethod method, int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest request(HttpMethod method, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest request(HttpMethod method, String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest request(HttpMethod method, String requestURI, Handler<HttpClientResponse> responseHandler) {
        return doRequest(method, requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest requestAbs(HttpMethod method, String absoluteURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest requestAbs(HttpMethod method, String absoluteURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest get(int port, String host, String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest get(String host, String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest get(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest get(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest get(String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest get(String uri, Handler<HttpClientResponse> responseHandler) {
        return doRequest(HttpMethod.GET, uri, responseHandler);
    }

    @Override
    public HttpClientRequest getAbs(String absoluteURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest getAbs(String absoluteURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient getNow(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient getNow(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest head(String uri, Handler<HttpClientResponse> responseHandler) {
        return doRequest(HttpMethod.HEAD, uri, responseHandler);
    }

    @Override
    public HttpClientRequest headAbs(String absoluteURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest headAbs(String absoluteURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient headNow(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient headNow(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient headNow(String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest options(int port, String host, String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest options(String host, String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest options(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest options(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest options(String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest post(String uri, Handler<HttpClientResponse> responseHandler) {
        return doRequest(HttpMethod.POST, uri, responseHandler);
    }

    @Override
    public HttpClientRequest postAbs(String absoluteURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest postAbs(String absoluteURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest head(int port, String host, String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest head(String host, String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest head(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest head(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest head(String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest put(String uri, Handler<HttpClientResponse> responseHandler) {
        return doRequest(HttpMethod.PUT, uri, responseHandler);
    }

    @Override
    public HttpClientRequest putAbs(String absoluteURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest putAbs(String absoluteURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest delete(int port, String host, String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest delete(String host, String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest delete(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest delete(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest delete(String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest delete(String uri, Handler<HttpClientResponse> responseHandler) {
        return doRequest(HttpMethod.DELETE, uri, responseHandler);
    }

    @Override
    public HttpClientRequest deleteAbs(String absoluteURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest deleteAbs(String absoluteURI, Handler<HttpClientResponse> responseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(String host, String requestURI, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(String host, String requestURI, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, MultiMap headers, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, MultiMap headers, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(String host, String requestURI, MultiMap headers, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(String host, String requestURI, MultiMap headers, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, MultiMap headers, WebsocketVersion version, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, MultiMap headers, WebsocketVersion version, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(String host, String requestURI, MultiMap headers, WebsocketVersion version, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(String host, String requestURI, MultiMap headers, WebsocketVersion version, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(String host, String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(String host, String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(String requestURI, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(String requestURI, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(String requestURI, MultiMap headers, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(String requestURI, MultiMap headers, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(String requestURI, MultiMap headers, WebsocketVersion version, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(String requestURI, MultiMap headers, WebsocketVersion version, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient websocket(String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public WebSocketStream websocketStream(int port, String host, String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public WebSocketStream websocketStream(String host, String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public WebSocketStream websocketStream(int port, String host, String requestURI, MultiMap headers) {
        throw new UnsupportedOperationException();
    }

    @Override
    public WebSocketStream websocketStream(String host, String requestURI, MultiMap headers) {
        throw new UnsupportedOperationException();
    }

    @Override
    public WebSocketStream websocketStream(int port, String host, String requestURI, MultiMap headers, WebsocketVersion version) {
        throw new UnsupportedOperationException();
    }

    @Override
    public WebSocketStream websocketStream(String host, String requestURI, MultiMap headers, WebsocketVersion version) {
        throw new UnsupportedOperationException();
    }

    @Override
    public WebSocketStream websocketStream(int port, String host, String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols) {
        throw new UnsupportedOperationException();
    }

    @Override
    public WebSocketStream websocketStream(String host, String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols) {
        throw new UnsupportedOperationException();
    }

    @Override
    public WebSocketStream websocketStream(String requestURI) {
        throw new UnsupportedOperationException();
    }

    @Override
    public WebSocketStream websocketStream(String requestURI, MultiMap headers) {
        throw new UnsupportedOperationException();
    }

    @Override
    public WebSocketStream websocketStream(String requestURI, MultiMap headers, WebsocketVersion version) {
        throw new UnsupportedOperationException();
    }

    @Override
    public WebSocketStream websocketStream(String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {}

    @Override
    public boolean isMetricsEnabled() {
        throw new UnsupportedOperationException();
    }
}

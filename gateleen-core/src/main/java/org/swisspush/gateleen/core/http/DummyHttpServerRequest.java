package org.swisspush.gateleen.core.http;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.security.cert.X509Certificate;

/**
 * Dummy class implementing {@link HttpServerRequest}. Override this class for your needs.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class DummyHttpServerRequest implements HttpServerRequest {

    @Override public HttpVersion version() {
        throw new UnsupportedOperationException();
    }

    @Override public HttpMethod method() {
        throw new UnsupportedOperationException();
    }

    @Override public String uri() {
        throw new UnsupportedOperationException();
    }

    @Override public String path() {
        throw new UnsupportedOperationException();
    }

    @Override public String query() {
        throw new UnsupportedOperationException();
    }

    @Override public HttpServerResponse response() {
        throw new UnsupportedOperationException();
    }

    @Override public MultiMap headers() {
        throw new UnsupportedOperationException();
    }

    @Override public String getHeader(String headerName) { throw new UnsupportedOperationException(); }

    @Override public String getHeader(CharSequence headerName) { throw new UnsupportedOperationException(); }

    @Override public MultiMap params() {
        throw new UnsupportedOperationException();
    }

    @Override public String getParam(String paramName) { throw new UnsupportedOperationException(); }

    @Override public SocketAddress remoteAddress() {
        throw new UnsupportedOperationException();
    }

    @Override public SocketAddress localAddress() {
        throw new UnsupportedOperationException();
    }

    @Override public X509Certificate[] peerCertificateChain() throws SSLPeerUnverifiedException {
        return new X509Certificate[0];
    }

    @Override public String absoluteURI() {
        throw new UnsupportedOperationException();
    }

    @Override public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
        throw new UnsupportedOperationException();
    }

    @Override public NetSocket netSocket() {
        throw new UnsupportedOperationException();
    }

    @Override public HttpServerRequest setExpectMultipart(boolean expect) { throw new UnsupportedOperationException(); }

    @Override public boolean isExpectMultipart() { throw new UnsupportedOperationException(); }

    @Override public HttpServerRequest uploadHandler(Handler<HttpServerFileUpload> uploadHandler) {
        throw new UnsupportedOperationException();
    }

    @Override public MultiMap formAttributes() {
        throw new UnsupportedOperationException();
    }

    @Override public String getFormAttribute(String attributeName) { throw new UnsupportedOperationException(); }

    @Override public ServerWebSocket upgrade() { throw new UnsupportedOperationException(); }

    @Override public boolean isEnded() { throw new UnsupportedOperationException(); }

    @Override public HttpServerRequest endHandler(Handler<Void> endHandler) {
        throw new UnsupportedOperationException();
    }

    @Override public HttpServerRequest handler(Handler<Buffer> handler) {
        throw new UnsupportedOperationException();
    }

    @Override public HttpServerRequest pause() {
        throw new UnsupportedOperationException();
    }

    @Override public HttpServerRequest resume() {
        throw new UnsupportedOperationException();
    }

    @Override public HttpServerRequest exceptionHandler(Handler<Throwable> handler) {
        throw new UnsupportedOperationException();
    }
}

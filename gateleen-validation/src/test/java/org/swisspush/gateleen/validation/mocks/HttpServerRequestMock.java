package org.swisspush.gateleen.validation.mocks;

import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import org.swisspush.gateleen.core.http.FastFailHttpServerRequest;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.security.cert.X509Certificate;

/**
 * Mock for the HttpServerRequest class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class HttpServerRequestMock extends FastFailHttpServerRequest {

    private String bodyContent;

    public void setBodyContent(String bodyContent) {
        this.bodyContent = bodyContent;
    }

    @Override
    public HttpVersion version() {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpMethod method() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String rawMethod() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isSSL() {
        return false;
    }

    @Override
    public @Nullable String scheme() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String uri() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String path() {
        return "";
    }

    @Override
    public String query() {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable HostAndPort authority() {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable HostAndPort authority(boolean real) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable String host() {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse response() {
        return new HttpServerResponseMock();
    }

    @Override
    public MultiMap headers() {
        return MultiMap.caseInsensitiveMultiMap();
    }

    @Override
    public String getHeader(String headerName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getHeader(CharSequence headerName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerRequest setParamsCharset(String charset) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getParamsCharset() {
        throw new UnsupportedOperationException();
    }

    @Override
    public MultiMap params() {
        return MultiMap.caseInsensitiveMultiMap();
    }

    @Override
    public MultiMap params(boolean semicolonIsNormalChar) {
        return params();
    }

    @Override
    public String getParam(String paramName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public SocketAddress remoteAddress() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SocketAddress localAddress() {
        throw new UnsupportedOperationException();
    }

    @Override
    public X509Certificate[] peerCertificateChain() throws SSLPeerUnverifiedException {
        return new X509Certificate[0];
    }

    @Override
    public String absoluteURI() {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
        bodyHandler.handle(Buffer.buffer(this.bodyContent));
        return this;
    }

    @Override
    public NetSocket netSocket() {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerRequest setExpectMultipart(boolean expect) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isExpectMultipart() {
        return false;
    }

    @Override
    public HttpServerRequest uploadHandler(Handler<HttpServerFileUpload> uploadHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public MultiMap formAttributes() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getFormAttribute(String attributeName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServerWebSocket upgrade() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isEnded() {
        return false;
    }

    @Override
    public HttpServerRequest customFrameHandler(Handler<HttpFrame> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpConnection connection() {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerRequest endHandler(Handler<Void> endHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerRequest handler(Handler<Buffer> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerRequest pause() {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerRequest resume() {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerRequest exceptionHandler(Handler<Throwable> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Context context() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object metric() {
        throw new UnsupportedOperationException();
    }
}

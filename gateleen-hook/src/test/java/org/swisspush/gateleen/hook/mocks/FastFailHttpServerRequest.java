package org.swisspush.gateleen.hook.mocks;

import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.security.cert.X509Certificate;


/**
 *
 * A {@link HttpServerRequest} throwing an exception no matter which method got
 * called.
 *
 * This is useful for testing. For testing inherit from this and override the
 * methods you need to mock.
 *
 */
public class FastFailHttpServerRequest implements HttpServerRequest {

    private static final String msg = "Mock: Override this method to mock your expected behaviour.";


    @Override
    public HttpServerRequest exceptionHandler(Handler<Throwable> handler) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerRequest handler(Handler<Buffer> handler) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerRequest pause() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerRequest resume() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerRequest endHandler(Handler<Void> endHandler) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpVersion version() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpMethod method() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public String rawMethod() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public boolean isSSL() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public @Nullable String scheme() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public String uri() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public @Nullable String path() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public @Nullable String query() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public @Nullable String host() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerResponse response() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public MultiMap headers() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public @Nullable String getHeader(String headerName) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public String getHeader(CharSequence headerName) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public MultiMap params() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public @Nullable String getParam(String paramName) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public SocketAddress remoteAddress() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public SocketAddress localAddress() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public SSLSession sslSession() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public X509Certificate[] peerCertificateChain() throws SSLPeerUnverifiedException {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public String absoluteURI() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public NetSocket netSocket() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerRequest setExpectMultipart(boolean expect) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public boolean isExpectMultipart() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerRequest uploadHandler(@Nullable Handler<HttpServerFileUpload> uploadHandler) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public MultiMap formAttributes() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public @Nullable String getFormAttribute(String attributeName) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public ServerWebSocket upgrade() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public boolean isEnded() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpServerRequest customFrameHandler(Handler<HttpFrame> handler) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public HttpConnection connection() {
        throw new UnsupportedOperationException( msg );
    }
}

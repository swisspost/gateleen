package org.swisspush.gateleen.core.http;

import io.netty.handler.codec.DecoderResult;
import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.http.impl.HttpServerRequestInternal;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.security.cert.X509Certificate;
import java.util.Set;


/**
 *
 * A {@link HttpServerRequest} throwing an exception no matter which method got
 * called.
 *
 * This is useful for testing. For testing inherit from this and override the
 * methods you need to mock.
 *
 */
public abstract class FastFailHttpServerRequest extends HttpServerRequestInternal {

    String msg = "Mock: Override this method to mock your expected behaviour.";


    public HttpServerRequest exceptionHandler(Handler<Throwable> handler) {
        throw new UnsupportedOperationException( msg );
    }

    public HttpServerRequest handler(Handler<Buffer> handler) {
        throw new UnsupportedOperationException( msg );
    }

    public HttpServerRequest pause() {
        throw new UnsupportedOperationException( msg );
    }

    public HttpServerRequest resume() {
        throw new UnsupportedOperationException( msg );
    }

    public HttpServerRequest fetch(long amount) {
        throw new UnsupportedOperationException( msg );
    }

    public HttpServerRequest endHandler(Handler<Void> endHandler) {
        throw new UnsupportedOperationException( msg );
    }

    public HttpVersion version() {
        throw new UnsupportedOperationException( msg );
    }

    public HttpMethod method() {
        throw new UnsupportedOperationException( msg );
    }

    public String rawMethod() {
        throw new UnsupportedOperationException( msg );
    }

    public boolean isSSL() {
        throw new UnsupportedOperationException( msg );
    }

    public @Nullable String scheme() {
        throw new UnsupportedOperationException( msg );
    }

    public String uri() {
        throw new UnsupportedOperationException( msg );
    }

    public @Nullable String path() {
        throw new UnsupportedOperationException( msg );
    }

    public @Nullable String query() {
        throw new UnsupportedOperationException( msg );
    }

    public @Nullable String host() {
        throw new UnsupportedOperationException( msg );
    }

    public long bytesRead() {
        throw new UnsupportedOperationException( msg );
    }

    public HttpServerResponse response() {
        throw new UnsupportedOperationException( msg );
    }

    public MultiMap headers() {
        throw new UnsupportedOperationException( msg );
    }

    public @Nullable String getHeader(String headerName) {
        throw new UnsupportedOperationException( msg );
    }

    public String getHeader(CharSequence headerName) {
        throw new UnsupportedOperationException( msg );
    }

    public MultiMap params() {
        throw new UnsupportedOperationException( msg );
    }

    public @Nullable String getParam(String paramName) {
        throw new UnsupportedOperationException( msg );
    }

    public SocketAddress remoteAddress() {
        throw new UnsupportedOperationException( msg );
    }

    public SocketAddress localAddress() {
        throw new UnsupportedOperationException( msg );
    }

    public SSLSession sslSession() {
        throw new UnsupportedOperationException( msg );
    }

    public X509Certificate[] peerCertificateChain() throws SSLPeerUnverifiedException {
        throw new UnsupportedOperationException( msg );
    }

    public String absoluteURI() {
        throw new UnsupportedOperationException( msg );
    }

    public NetSocket netSocket() {
        throw new UnsupportedOperationException( msg );
    }

    public HttpServerRequest setExpectMultipart(boolean expect) {
        throw new UnsupportedOperationException( msg );
    }

    public boolean isExpectMultipart() {
        throw new UnsupportedOperationException( msg );
    }

    public HttpServerRequest uploadHandler(@Nullable Handler<HttpServerFileUpload> uploadHandler) {
        throw new UnsupportedOperationException( msg );
    }

    public MultiMap formAttributes() {
        throw new UnsupportedOperationException( msg );
    }

    public @Nullable String getFormAttribute(String attributeName) {
        throw new UnsupportedOperationException( msg );
    }

    public ServerWebSocket upgrade() {
        throw new UnsupportedOperationException( msg );
    }

    public boolean isEnded() {
        throw new UnsupportedOperationException( msg );
    }

    public HttpServerRequest customFrameHandler(Handler<HttpFrame> handler) {
        throw new UnsupportedOperationException( msg );
    }

    public HttpConnection connection() {
        throw new UnsupportedOperationException( msg );
    }

    public HttpServerRequest streamPriorityHandler(Handler<StreamPriority> handler) {
        throw new UnsupportedOperationException( msg );
    }


    public Future<Buffer> body() {
        throw new UnsupportedOperationException( msg );
    }


    public Future<Void> end() {
        throw new UnsupportedOperationException( msg );
    }


    public Future<NetSocket> toNetSocket() {
        throw new UnsupportedOperationException( msg );
    }


    public Future<ServerWebSocket> toWebSocket() {
        throw new UnsupportedOperationException( msg );
    }


    public DecoderResult decoderResult() {
        throw new UnsupportedOperationException( msg );
    }


    public @Nullable Cookie getCookie(String name) {
        throw new UnsupportedOperationException( msg );
    }


    public @Nullable Cookie getCookie(String name, String domain, String path) {
        throw new UnsupportedOperationException( msg );
    }


    public Set<Cookie> cookies(String name) {
        throw new UnsupportedOperationException( msg );
    }


    public Set<Cookie> cookies() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public Context context() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public Object metric() {
        throw new UnsupportedOperationException( msg );
    }
}

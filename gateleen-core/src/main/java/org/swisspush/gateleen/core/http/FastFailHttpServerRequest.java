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
public interface FastFailHttpServerRequest extends HttpServerRequestInternal {

    String msg = "Mock: Override this method to mock your expected behaviour.";


    default HttpServerRequest exceptionHandler(Handler<Throwable> handler) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerRequest handler(Handler<Buffer> handler) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerRequest pause() {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerRequest resume() {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerRequest fetch(long amount) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerRequest endHandler(Handler<Void> endHandler) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpVersion version() {
        throw new UnsupportedOperationException( msg );
    }

    default HttpMethod method() {
        throw new UnsupportedOperationException( msg );
    }

    default String rawMethod() {
        throw new UnsupportedOperationException( msg );
    }

    default boolean isSSL() {
        throw new UnsupportedOperationException( msg );
    }

    default @Nullable String scheme() {
        throw new UnsupportedOperationException( msg );
    }

    default String uri() {
        throw new UnsupportedOperationException( msg );
    }

    default @Nullable String path() {
        throw new UnsupportedOperationException( msg );
    }

    default @Nullable String query() {
        throw new UnsupportedOperationException( msg );
    }

    default @Nullable String host() {
        throw new UnsupportedOperationException( msg );
    }

    default long bytesRead() {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerResponse response() {
        throw new UnsupportedOperationException( msg );
    }

    default MultiMap headers() {
        throw new UnsupportedOperationException( msg );
    }

    default @Nullable String getHeader(String headerName) {
        throw new UnsupportedOperationException( msg );
    }

    default String getHeader(CharSequence headerName) {
        throw new UnsupportedOperationException( msg );
    }

    default MultiMap params() {
        throw new UnsupportedOperationException( msg );
    }

    default @Nullable String getParam(String paramName) {
        throw new UnsupportedOperationException( msg );
    }

    default SocketAddress remoteAddress() {
        throw new UnsupportedOperationException( msg );
    }

    default SocketAddress localAddress() {
        throw new UnsupportedOperationException( msg );
    }

    default SSLSession sslSession() {
        throw new UnsupportedOperationException( msg );
    }

    default X509Certificate[] peerCertificateChain() throws SSLPeerUnverifiedException {
        throw new UnsupportedOperationException( msg );
    }

    default String absoluteURI() {
        throw new UnsupportedOperationException( msg );
    }

    default NetSocket netSocket() {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerRequest setExpectMultipart(boolean expect) {
        throw new UnsupportedOperationException( msg );
    }

    default boolean isExpectMultipart() {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerRequest uploadHandler(@Nullable Handler<HttpServerFileUpload> uploadHandler) {
        throw new UnsupportedOperationException( msg );
    }

    default MultiMap formAttributes() {
        throw new UnsupportedOperationException( msg );
    }

    default @Nullable String getFormAttribute(String attributeName) {
        throw new UnsupportedOperationException( msg );
    }

    default ServerWebSocket upgrade() {
        throw new UnsupportedOperationException( msg );
    }

    default boolean isEnded() {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerRequest customFrameHandler(Handler<HttpFrame> handler) {
        throw new UnsupportedOperationException( msg );
    }

    default HttpConnection connection() {
        throw new UnsupportedOperationException( msg );
    }

    default HttpServerRequest streamPriorityHandler(Handler<StreamPriority> handler) {
        throw new UnsupportedOperationException( msg );
    }


    default Future<Buffer> body() {
        throw new UnsupportedOperationException( msg );
    }


    default Future<Void> end() {
        throw new UnsupportedOperationException( msg );
    }


    default Future<NetSocket> toNetSocket() {
        throw new UnsupportedOperationException( msg );
    }


    default Future<ServerWebSocket> toWebSocket() {
        throw new UnsupportedOperationException( msg );
    }


    default DecoderResult decoderResult() {
        throw new UnsupportedOperationException( msg );
    }


    default @Nullable Cookie getCookie(String name) {
        throw new UnsupportedOperationException( msg );
    }


    default @Nullable Cookie getCookie(String name, String domain, String path) {
        throw new UnsupportedOperationException( msg );
    }


    default Set<Cookie> cookies(String name) {
        throw new UnsupportedOperationException( msg );
    }


    default Set<Cookie> cookies() {
        throw new UnsupportedOperationException( msg );
    }


    @Override
    default Context context() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    default Object metric() {
        throw new UnsupportedOperationException( msg );
    }
}

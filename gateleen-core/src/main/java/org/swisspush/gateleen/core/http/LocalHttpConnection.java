package org.swisspush.gateleen.core.http;

import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.GoAway;
import io.vertx.core.http.Http2Settings;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.net.SocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.security.cert.X509Certificate;
import java.security.cert.Certificate;
import java.util.List;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * this is a mock-connection object which silently ignores (nearly) HTTP/1.1 relevant method calls and never throws exceptions
 * we need this to be able to set appropriate exception- and close-handlers in e.g. Gateleen's Forwarder (gateleen-routing)
 */
public class LocalHttpConnection implements HttpConnection {

    private static final Logger log = getLogger(LocalHttpConnection.class);

    @Override
    public HttpConnection goAway(long errorCode, int lastStreamId, Buffer debugData) {
        throw new UnsupportedOperationException("LocalConnection don't support this");
    }

    @Override
    public HttpConnection goAwayHandler(@Nullable Handler<GoAway> handler) {
        throw new UnsupportedOperationException("LocalConnection don't support this");
    }

    @Override
    public HttpConnection shutdownHandler(@Nullable Handler<Void> handler) {
        throw new UnsupportedOperationException("LocalConnection don't support this");
    }

    @Override
    public void shutdown(long timeout, Handler<AsyncResult<Void>> handler) {
        throw new UnsupportedOperationException("LocalConnection don't support this");
    }

    @Override
    public Future<Void> shutdown(long timeoutMs) {
        throw new UnsupportedOperationException("LocalConnection don't support this");
    }

    @Override
    public HttpConnection closeHandler(Handler<Void> handler) {
        log.warn("Happy debugging, as this impl is going to ignore your closeHandler anyway",
                new Exception("may this stacktrace help you"));
        return this;
    }

    @Override
    public Future<Void> close() {
        throw new UnsupportedOperationException("LocalConnection don't support this");
    }


    @Override
    public Http2Settings settings() {
        throw new UnsupportedOperationException("LocalConnection don't support this");
    }

    @Override
    public Future<Void> updateSettings(Http2Settings settings) {
        throw new UnsupportedOperationException("LocalConnection don't support this");
    }

    @Override
    public HttpConnection updateSettings(Http2Settings settings, Handler<AsyncResult<Void>> completionHandler) {
        throw new UnsupportedOperationException("LocalConnection don't support this");
    }

    @Override
    public Http2Settings remoteSettings() {
        throw new UnsupportedOperationException("LocalConnection don't support this");
    }

    @Override
    public HttpConnection remoteSettingsHandler(Handler<Http2Settings> handler) {
        throw new UnsupportedOperationException("LocalConnection don't support this");
    }

    @Override
    public HttpConnection ping(Buffer data, Handler<AsyncResult<Buffer>> pongHandler) {
        throw new UnsupportedOperationException("LocalConnection don't support this");
    }

    @Override
    public Future<Buffer> ping(Buffer data) {
        throw new UnsupportedOperationException("LocalConnection don't support this");
    }

    @Override
    public HttpConnection pingHandler(@Nullable Handler<Buffer> handler) {
        throw new UnsupportedOperationException("LocalConnection don't support this");
    }

    @Override
    public HttpConnection exceptionHandler(Handler<Throwable> handler) {
        log.warn("Happy debugging, as this impl just ignores your exceptionHandler anyway",
                new Exception("stacktrace"));
        return this;
    }

    @Override
    public SocketAddress remoteAddress() {
        throw new UnsupportedOperationException("LocalConnection don't support this");
    }

    @Override
    public SocketAddress localAddress() {
        throw new UnsupportedOperationException("LocalConnection don't support this");
    }

    @Override
    public boolean isSsl() {
        return false;
    }

    @Override
    public SSLSession sslSession() {
        throw new UnsupportedOperationException("LocalConnection don't support this");
    }

    @Override
    public X509Certificate[] peerCertificateChain() throws SSLPeerUnverifiedException {
        throw new UnsupportedOperationException("LocalConnection don't support this");
    }

    @Override
    public List<Certificate> peerCertificates() throws SSLPeerUnverifiedException {
        throw new UnsupportedOperationException("LocalConnection don't support this");
    }

    @Override
    public String indicatedServerName() {
        throw new UnsupportedOperationException("LocalConnection don't support this");
    }
}

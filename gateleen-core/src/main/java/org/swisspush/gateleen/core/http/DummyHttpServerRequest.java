package org.swisspush.gateleen.core.http;

import io.vertx.core.http.HttpServerRequest;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.security.cert.X509Certificate;


/**
 * Dummy class implementing {@link HttpServerRequest}. Override this class for your needs.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class DummyHttpServerRequest extends FastFailHttpServerRequest {

    @Override public boolean isSSL() { return false; }

    @Override
    public String getHeader(String headerName) {
        return null;
    }

    @Override public X509Certificate[] peerCertificateChain() throws SSLPeerUnverifiedException {
        return new X509Certificate[0];
    }

}

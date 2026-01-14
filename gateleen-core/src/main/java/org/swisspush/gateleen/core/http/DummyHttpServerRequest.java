package org.swisspush.gateleen.core.http;

import io.netty.handler.codec.http.QueryStringDecoder;
import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.net.HostAndPort;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.security.cert.X509Certificate;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;


/**
 * Dummy class implementing {@link HttpServerRequest}. Override this class for your needs.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class DummyHttpServerRequest extends FastFailHttpServerRequest {

    private Charset paramsCharset = StandardCharsets.UTF_8;
    private MultiMap params;

    @Override
    public HttpVersion version() {
        return HttpVersion.HTTP_1_0;
    }

    @Override public boolean isSSL() { return false; }

    @Override
    public @Nullable HostAndPort authority() {
        return null;
    }

    @Override
    public @Nullable HostAndPort authority(boolean real) {
        return null;
    }

    @Override
    public String getHeader(String headerName) {
        return null;
    }

    @Override
    public HttpServerRequest setParamsCharset(String charset) {
        Objects.requireNonNull(charset, "Charset must not be null");
        Charset current = paramsCharset;
        paramsCharset = Charset.forName(charset);
        if (!paramsCharset.equals(current)) {
            params = null;
        }
        return this;
    }

    @Override
    public String getParamsCharset() {
        return paramsCharset.name();
    }

    @Override
    public MultiMap params() {
        if (params == null) {
            QueryStringDecoder queryStringDecoder = new QueryStringDecoder(uri(), paramsCharset);
            Map<String, List<String>> prms = queryStringDecoder.parameters();
            params = new HeadersMultiMap();
            if (!prms.isEmpty()) {
                for (Map.Entry<String, List<String>> entry : prms.entrySet()) {
                    params.add(entry.getKey(), entry.getValue());
                }
            }
        }
        return params;
    }

    @Override
    public MultiMap params(boolean b) {
        return params();
    }

    @Override
    public String getParam(String paramName) {
        return params.get(paramName);
    }

    @Override public X509Certificate[] peerCertificateChain() throws SSLPeerUnverifiedException {
        return new X509Certificate[0];
    }
}

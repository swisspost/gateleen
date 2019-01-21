package org.swisspush.gateleen.core.http;

import io.netty.handler.codec.http.QueryStringDecoder;
import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.http.impl.headers.VertxHttpHeaders;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.net.impl.SocketAddressImpl;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.*;

import javax.net.ssl.SSLSession;
import javax.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bridges a HttpClientRequest to a HttpServerRequest sent to a request handler.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class LocalHttpClientRequest extends BufferBridge implements FastFailHttpClientRequest {

    private MultiMap headers = new VertxHttpHeaders();
    private MultiMap params;
    private HttpMethod method;
    private String uri;
    private String path;
    private String query;
    private HttpServerResponse serverResponse;
    private final HttpConnection connection;
    private Handler<RoutingContext> routingContextHandler;
    private boolean bound = false;

    private static final SocketAddress address = new SocketAddressImpl(0, "localhost");

    private HttpServerRequest serverRequest = new FastFailHttpServerRequest() {
        @Override
        public HttpVersion version() {
            return HttpVersion.HTTP_1_0;
        }

        @Override
        public HttpMethod method() {
            return method;
        }

        @Override
        public boolean isSSL() { return false; }

        @Override
        public String uri() {
            return uri;
        }

        @Override
        public String path() {
            if (path == null) {
                path = UriParser.path(uri());
            }
            return path;
        }

        @Override
        public String query() {
            if (query == null) {
                query = UriParser.query(uri());
            }
            return query;
        }

        @Override
        public MultiMap params() {
            if (params == null) {
                QueryStringDecoder queryStringDecoder = new QueryStringDecoder(uri());
                Map<String, List<String>> prms = queryStringDecoder.parameters();
                params = new VertxHttpHeaders();
                if (!prms.isEmpty()) {
                    for (Map.Entry<String, List<String>> entry: prms.entrySet()) {
                        params.add(entry.getKey(), entry.getValue());
                    }
                }
            }
            return params;
        }

        @Override
        public String getParam(String paramName) {
            return params().get(paramName);
        }

        @Override
        public MultiMap headers() {
            return headers;
        }

        @Override
        public String getHeader(String headerName) {
            return headers().get(headerName);
        }

        @Override
        public String getHeader(CharSequence headerName) {
            return headers().get(headerName);
        }

        @Override
        public SocketAddress remoteAddress() {
            return address;
        }

        @Override
        public SocketAddress localAddress() {
            return address;
        }

        @Override
        public SSLSession sslSession() {
            return null;
        }

        @Override
        public X509Certificate[] peerCertificateChain() {
            return new X509Certificate[0];
        }

        @Override
        public String absoluteURI() {
            return "local:" + uri;
        }

        @Override
        public NetSocket netSocket() {
            return null;
        }

        @Override
        public HttpServerRequest setExpectMultipart(boolean expect) {
            return null;
        }

        @Override
        public boolean isExpectMultipart() {
            return false;
        }

        @Override
        public HttpConnection connection() { return connection; }

        @Override
        public HttpServerRequest endHandler(Handler<Void> handler) {
            setEndHandler(handler);
            return this;
        }

        @Override
        public HttpServerRequest handler(Handler<Buffer> handler) {
            setDataHandler(handler);
            // As soon as the dataHandler is set, we can dump the queue in it.
            pump();
            return this;
        }

        @Override
        public HttpServerRequest pause() {
            return this;
        }

        @Override
        public HttpServerRequest resume() {
            return this;
        }

        @Override
        public HttpServerResponse response() {
            return serverResponse;
        }

        @Override
        public HttpServerRequest exceptionHandler(Handler<Throwable> handler) {
            return this;
        }

        @Override
        public boolean isEnded() {
            return false;
        }
    };

    private RoutingContext routingContext = new RoutingContext() {
        @Override
        public HttpServerRequest request() {
            return serverRequest;
        }

        @Override
        public HttpServerResponse response() {
            return serverResponse;
        }

        @Override
        public void next() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void fail(int statusCode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void fail(Throwable throwable) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RoutingContext put(String key, Object obj) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T get(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T remove(String key) { throw new UnsupportedOperationException(); }

        @Override
        public Map<String, Object> data() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Vertx vertx() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String mountPoint() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Route currentRoute() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String normalisedPath() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Cookie getCookie(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RoutingContext addCookie(Cookie cookie) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Cookie removeCookie(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @Nullable Cookie removeCookie(String s, boolean b) { throw new UnsupportedOperationException(); }

        @Override
        public int cookieCount() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<Cookie> cookies() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getBodyAsString() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getBodyAsString(String encoding) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonObject getBodyAsJson() {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonArray getBodyAsJsonArray() { throw new UnsupportedOperationException(); }

        @Override
        public Buffer getBody() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<FileUpload> fileUploads() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Session session() {
            throw new UnsupportedOperationException();
        }

        @Override
        public User user() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Throwable failure() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int statusCode() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getAcceptableContentType() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ParsedHeaderValues parsedHeaders() {
            throw new UnsupportedOperationException();
        }

         @Override
        public int addHeadersEndHandler(Handler<Void> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeHeadersEndHandler(int handlerID) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int addBodyEndHandler(Handler<Void> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeBodyEndHandler(int handlerID) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean failed() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setBody(Buffer body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setSession(Session session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setUser(User user) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearUser() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setAcceptableContentType(String contentType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void reroute(String path) { throw new UnsupportedOperationException(); }

        @Override
        public void reroute(HttpMethod method, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Locale> acceptableLocales() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<LanguageHeader> acceptableLanguages() { throw new UnsupportedOperationException(); }

        @Override
        public Locale preferredLocale() { throw new UnsupportedOperationException(); }

        @Override
        public LanguageHeader preferredLanguage() { throw new UnsupportedOperationException(); }

        @Override
        public Map<String, String> pathParams() { throw new UnsupportedOperationException(); }

        @Override
        public @Nullable String pathParam(String name) { throw new UnsupportedOperationException(); }

        @Override
        public MultiMap queryParams() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @Nullable List<String> queryParam(String query) {
            throw new UnsupportedOperationException();
        }
    };

    public LocalHttpClientRequest(HttpMethod method, String uri, Vertx vertx, Handler<RoutingContext> routingContextHandler, HttpServerResponse response) {
        super(vertx);
        this.method = method;
        this.uri = uri;
        this.routingContextHandler = routingContextHandler;
        this.serverResponse = response;
        this.connection = new LocalHttpConnection();
    }

    @Override
    public HttpClientRequest setChunked(boolean chunked) {
        return this;
    }

    @Override
    public boolean isChunked() {
        return false;
    }

    @Override
    public HttpMethod method() {
        return method;
    }

    @Override
    public String getRawMethod() { return null; }

    @Override
    public HttpClientRequest setRawMethod(String method) { return this; }

    @Override
    public String absoluteURI() {
        return null;
    }

    @Override
    public String uri() {
        return uri;
    }

    @Override
    public String path() { return null; }

    @Override
    public String query() { return null; }

    @Override
    public HttpClientRequest setHost(String host) { return this; }

    @Override
    public String getHost() { return null; }

    @Override
    public MultiMap headers() {
        return headers;
    }

    @Override
    public HttpClientRequest putHeader(String name, String value) {
        headers().set(name, value);
        return this;
    }

    @Override
    public HttpClientRequest putHeader(CharSequence name, CharSequence value) {
        headers().set(name, value);
        return this;
    }

    @Override
    public HttpClientRequest putHeader(String name, Iterable<String> values) {
        for(String value : values) {
            headers().add(name, value);
        }
        return this;
    }

    @Override
    public HttpClientRequest putHeader(CharSequence name, Iterable<CharSequence> values) {
        for(CharSequence value : values) {
            headers().add(name, value);
        }
        return this;
    }

    @Override
    public HttpClientRequest write(Buffer chunk) {
        ensureBound();
        doWrite(chunk);
        return this;
    }

    private void ensureBound() {
        if(!bound) {
            bound = true;
            routingContextHandler.handle(routingContext);
        }
    }

    @Override
    public HttpClientRequest write(String chunk) {
        write(Buffer.buffer(chunk));
        return this;
    }

    @Override
    public HttpClientRequest write(String chunk, String enc) {
        write(Buffer.buffer(chunk, enc));
        return this;
    }

    @Override
    public HttpClientRequest sendHead() {
        return this;
    }

    @Override
    public HttpClientRequest sendHead(Handler<HttpVersion> completionHandler) { return this; }

    @Override
    public void end(String chunk) {
        write(chunk);
        end();
    }

    @Override
    public void end(String chunk, String enc) {
        write(chunk, enc);
        end();
    }

    @Override
    public void end(Buffer chunk) {
        write(chunk);
        end();
    }

    @Override
    public void end() {
        ensureBound();
        doEnd();
    }

    @Override
    public HttpClientRequest setTimeout(long timeoutMs) {
        return this;
    }

    @Override
    public HttpClientRequest pushHandler(Handler<HttpClientRequest> handler) { return this; }

    @Override
    public boolean reset() {
        return false;
    }

    @Override
    public boolean reset(long code) {
        return false;
    }

    @Override
    public HttpConnection connection() { return null; }

    @Override
    public HttpClientRequest connectionHandler(@Nullable Handler<HttpConnection> handler) { return this; }

    @Override
    public HttpClientRequest writeCustomFrame(int type, int flags, Buffer payload) { return this; }

    @Override
    public HttpClientRequest setWriteQueueMaxSize(int maxSize) {
        return this;
    }

    @Override
    public boolean writeQueueFull() {
        return false;
    }

    @Override
    public HttpClientRequest drainHandler(Handler<Void> handler) {
        return this;
    }

     @Override
    public HttpClientRequest exceptionHandler(Handler<Throwable> handler) {
        setExceptionHandler(handler);
        return this;
    }

    public void setMethod(HttpMethod method) {
        this.method = method;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }
}

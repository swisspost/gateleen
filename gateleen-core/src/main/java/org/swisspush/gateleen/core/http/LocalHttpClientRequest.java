package org.swisspush.gateleen.core.http;

import io.netty.handler.codec.http.QueryStringDecoder;
import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.StreamPriority;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.net.impl.SocketAddressImpl;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.LanguageHeader;
import io.vertx.ext.web.ParsedHeaderValues;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.Session;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;

import javax.net.ssl.SSLSession;
import javax.security.cert.X509Certificate;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.vertx.core.Future.failedFuture;
import static io.vertx.core.Future.succeededFuture;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Bridges a HttpClientRequest to a HttpServerRequest sent to a request handler.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class LocalHttpClientRequest extends BufferBridge implements HttpClientRequest {
    private static final Logger log = getLogger(LocalHttpClientRequest.class);
    private MultiMap headers = new HeadersMultiMap();
    private Charset paramsCharset = StandardCharsets.UTF_8;
    private MultiMap params;
    private HttpMethod method;
    private String uri;
    private String path;
    private String query;
    private HttpServerResponse serverResponse;
    private final HttpConnection connection;
    private Supplier<Handler<RoutingContext>> getRoutingContextHandler;
    private final GateleenExceptionFactory exceptionFactory;
    private final AtomicBoolean bound = new AtomicBoolean();

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
        public boolean isSSL() {
            return false;
        }

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
        public @Nullable HostAndPort authority() {
            return null;
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
        public SocketAddress remoteAddress() {
            return address;
        }

        @Override
        public SocketAddress localAddress() {
            return address;
        }

        @Override
        public SSLSession sslSession() {
            throw new UnsupportedOperationException();
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
        public HttpConnection connection() {
            return connection;
        }

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

        @Override
        public Future<Buffer> body() {
            Promise<Buffer> promise = Promise.promise();
            setBodyHandler(promise::complete);
            return promise.future();
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
        public void fail(int i, Throwable throwable) {
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
        public <T> T get(String s, T t) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T remove(String key) {
            throw new UnsupportedOperationException();
        }

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
        public String normalizedPath() {
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
        public @Nullable Cookie removeCookie(String s, boolean b) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int cookieCount() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Cookie> cookieMap() {
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
        public @Nullable JsonObject getBodyAsJson(int i) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @Nullable JsonArray getBodyAsJsonArray(int i) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonObject getBodyAsJson() {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonArray getBodyAsJsonArray() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Buffer getBody() {
            throw new UnsupportedOperationException();
        }

        @Override
        public RequestBody body() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<FileUpload> fileUploads() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancelAndCleanupFileUploads() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Session session() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isSessionAccessed() {
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
        public int addEndHandler(Handler<AsyncResult<Void>> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeEndHandler(int i) {
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
        public void reroute(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void reroute(HttpMethod method, String path) {
            throw new UnsupportedOperationException();
        }


        @Override
        public List<LanguageHeader> acceptableLanguages() {
            throw new UnsupportedOperationException();
        }


        @Override
        public LanguageHeader preferredLanguage() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, String> pathParams() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @Nullable String pathParam(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MultiMap queryParams() {
            throw new UnsupportedOperationException();
        }

        @Override
        public MultiMap queryParams(Charset charset) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @Nullable List<String> queryParam(String query) {
            throw new UnsupportedOperationException();
        }
    };

    public LocalHttpClientRequest(
        HttpMethod method,
        String uri,
        Vertx vertx,
        Supplier<Handler<RoutingContext>> getRoutingContextHandler,
        GateleenExceptionFactory exceptionFactory,
        HttpServerResponse response
    ) {
        super(vertx);
        assert getRoutingContextHandler != null : "getRoutingContextHandler != null";
        this.method = method;
        this.uri = uri;
        this.getRoutingContextHandler = getRoutingContextHandler;
        this.exceptionFactory = exceptionFactory;
        this.serverResponse = response;
        this.connection = new LocalHttpConnection();
        setExceptionHandler(defaultExcetionHandler(null));
    }

    private Handler<Throwable> defaultExcetionHandler(@Nullable Handler<Throwable> externalHandler) {
        return throwable -> {
            if (log.isDebugEnabled()) {
                log.error(
                        "A HTTP {} request to '{}' failed with reason {}",
                        method,
                        uri,
                        throwable.getMessage(),
                        throwable);
            } else {
                log.error(
                        "A HTTP {} request to '{}' failed with reason {}",
                        method,
                        uri,
                        throwable.getMessage());
            }
            if (externalHandler != null) {
                externalHandler.handle(throwable);
            }
        };
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
    public HttpMethod getMethod() {
        return method;
    }

    @Override
    public String absoluteURI() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getURI() {
        return uri;
    }

    @Override
    public HttpClientRequest setURI(String uri) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String path() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String query() {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest setHost(String host) {
        return this;
    }

    @Override
    public String getHost() {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest setPort(int port) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getPort() {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest setFollowRedirects(boolean followRedirects) {
        throw new UnsupportedOperationException(getClass().getName() +".setFollowRedirects(boolean)");
    }

    @Override
    public boolean isFollowRedirects() {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest setMaxRedirects(int maxRedirects) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getMaxRedirects() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int numberOfRedirections() {
        throw new UnsupportedOperationException();
    }

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
        for (String value : values) {
            headers().add(name, value);
        }
        return this;
    }

    @Override
    public HttpClientRequest putHeader(CharSequence name, Iterable<CharSequence> values) {
        for (CharSequence value : values) {
            headers().add(name, value);
        }
        return this;
    }

    @Override
    public HttpClientRequest traceOperation(String op) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String traceOperation() {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpVersion version() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Future<Void> sendHead() {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest sendHead(Handler<AsyncResult<Void>> completionHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void connect(Handler<AsyncResult<HttpClientResponse>> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Future<HttpClientResponse> connect() {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest response(Handler<AsyncResult<HttpClientResponse>> handler) {
        ((LocalHttpServerResponse) serverResponse).setHttpClientResponseHandler(handler);
        return this;
    }

    @Override
    public Future<HttpClientResponse> response() {
        HttpClientResponse response = ((LocalHttpServerResponse) serverResponse).clientResponse;
        return Future.succeededFuture(response);
    }


    private Future<Void> ensureBound() {
        if (bound.getAndSet(true)) {
            /* did that already somewhen earlier */
            return succeededFuture();
        }
        Handler<RoutingContext> routingContextHandler = getRoutingContextHandler.get();
        if (routingContextHandler == null) {
            /* I saw, there just SOMETIMES is NO handler?!? I didn't find out yet
             * what fancy use-case that is. Maybe its just fine and we can just
             * "not call" it? This is just a guess! It could as well be, that this
             * case is a serious error somewhere else, not providing this handler? */
            log.debug("There's no routingContextHandler. So how should we call it then?");
            return succeededFuture();
        }
        try {
            routingContextHandler.handle(routingContext);
        } catch (RuntimeException ex) {
            return failedFuture(ex);
        }
        return succeededFuture();
    }

    @Override
    public Future<Void> write(String chunk) {
        return write(Buffer.buffer(chunk));
    }

    @Override
    public Future<Void> write(String chunk, String enc) {
        return write(Buffer.buffer(chunk, enc));
    }

    @Override
    public Future<Void> write(Buffer data) {
        return ensureBound().map((Void nothing) -> {
            doWrite(data);
            return nothing;
        });
    }

    @Override
    public void write(Buffer data, Handler<AsyncResult<Void>> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void write(String chunk, Handler<AsyncResult<Void>> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void write(String chunk, String enc, Handler<AsyncResult<Void>> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest continueHandler(@Nullable Handler<Void> handler) {
        throw new UnsupportedOperationException(getClass().getName() +".continueHandler(Handler)");
    }

    @Override
    public HttpClientRequest earlyHintsHandler(@Nullable Handler<MultiMap> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest redirectHandler(@Nullable Function<HttpClientResponse, Future<HttpClientRequest>> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Future<Void> end(String chunk) {
        return write(chunk).onComplete(event -> end());
    }

    @Override
    public void end(String chunk, Handler<AsyncResult<Void>> handler) {
        throw new UnsupportedOperationException(getClass().getName() +".end(String,Handler)");
    }

    @Override
    public Future<Void> end(String chunk, String enc) {
        return write(chunk, enc).onComplete(event -> end());
    }

    @Override
    public void end(String chunk, String enc, Handler<AsyncResult<Void>> handler) {
        throw new UnsupportedOperationException(getClass().getName() +".end(String,String,Handler)");
    }

    @Override
    public Future<Void> end(Buffer chunk) {
        return write(chunk).onComplete(event -> end());
    }

    @Override
    public void end(Buffer chunk, Handler<AsyncResult<Void>> handler) {
        throw new UnsupportedOperationException(getClass().getName() +".end(Buffer,Handler)");
    }

    @Override
    public Future<Void> end() {
        return ensureBound().compose((Void nothing) -> {
            log.trace("end(): ensureBound() succeeded, calling doEnd() now");
            return doEnd();
        });
    }

    @Override
    public void end(Handler<AsyncResult<Void>> handler) {
        throw new UnsupportedOperationException(getClass().getName() +".end(Handler)");
    }

    @Override
    public HttpClientRequest setTimeout(long timeoutMs) {
        return this;
    }

    @Override
    public HttpClientRequest pushHandler(Handler<HttpClientRequest> handler) {
        return this;
    }

    @Override
    public boolean reset() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean reset(long code) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean reset(long code, Throwable cause) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpConnection connection() {
        // Cited from API specification:
        //     @return the {@link HttpConnection} associated with this request
        // As "no connection" is associated with this request, we return "no connection".
        log.debug("There's no connection associated with this request.");
        return null;
    }

    @Override
    public HttpClientRequest writeCustomFrame(int type, int flags, Buffer payload) {
        return this;
    }

    @Override
    public StreamPriority getStreamPriority() {
        throw new UnsupportedOperationException(getClass().getName() +".getStreamPriority()");
    }

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
        log.warn("stacktrace", exceptionFactory.newException("TODO impl drainHandler"));
        return this;
    }

    @Override
    public HttpClientRequest authority(HostAndPort authority) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpClientRequest exceptionHandler(Handler<Throwable> handler) {
        setExceptionHandler(defaultExcetionHandler(handler));
        return this;
    }

    public HttpClientRequest setMethod(HttpMethod method) {
        this.method = method;
        return this;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }
}

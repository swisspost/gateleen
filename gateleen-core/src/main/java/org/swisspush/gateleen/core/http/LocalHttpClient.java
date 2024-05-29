package org.swisspush.gateleen.core.http;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;

/**
 * Created by bovetl on 22.01.2015.
 */
public class LocalHttpClient extends AbstractHttpClient {

    private Handler<RoutingContext> wrappedRoutingContexttHandler;
    private Vertx vertx;
    private final GateleenExceptionFactory exceptionFactory;

    public LocalHttpClient(Vertx vertx, GateleenExceptionFactory exceptionFactory) {
        super(vertx);
        this.vertx = vertx;
        this.exceptionFactory = exceptionFactory;
    }

    public void setRoutingContexttHandler(Handler<RoutingContext> wrappedRoutingContexttHandler) {
        this.wrappedRoutingContexttHandler = wrappedRoutingContexttHandler;
    }

    @Override
    protected HttpClientRequest doRequest(HttpMethod method, String uri) {
        return new LocalHttpClientRequest(method, uri, vertx, wrappedRoutingContexttHandler, exceptionFactory, new LocalHttpServerResponse(vertx));
    }
}

package org.swisspush.gateleen.core.http;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;

import java.util.function.Supplier;


/**
 * Created by bovetl on 22.01.2015.
 */
public class LocalHttpClient extends AbstractHttpClient {

    private final Vertx vertx;
    private final Supplier<Handler<RoutingContext>> getRoutingContextHandler;
    private final GateleenExceptionFactory exceptionFactory;

    public LocalHttpClient(
            Vertx vertx,
            Supplier<Handler<RoutingContext>> getRoutingContext,
            GateleenExceptionFactory exceptionFactory
    ) {
        super(vertx);
        assert getRoutingContext != null : "getRoutingContext != null";
        this.vertx = vertx;
        this.getRoutingContextHandler = getRoutingContext;
        this.exceptionFactory = exceptionFactory;
    }

    @Override
    protected HttpClientRequest doRequest(HttpMethod method, String uri) {
        return new LocalHttpClientRequest(
                method, uri, vertx, getRoutingContextHandler, exceptionFactory,
                new LocalHttpServerResponse(vertx, exceptionFactory));
    }
}

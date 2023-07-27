package org.swisspush.gateleen.core.http;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;

/**
 * Created by bovetl on 22.01.2015.
 */
public class LocalHttpClient extends AbstractHttpClient {

    private Handler<RoutingContext> wrappedRoutingContexttHandler;
    private Vertx vertx;

    public LocalHttpClient(Vertx vertx) {
        super(vertx);
        this.vertx = vertx;
    }

    public void setRoutingContexttHandler(Handler<RoutingContext> wrappedRoutingContexttHandler) {
        this.wrappedRoutingContexttHandler = wrappedRoutingContexttHandler;
    }

    @Override
    protected HttpClientRequest doRequest(HttpMethod method, String uri) {
        return new LocalHttpClientRequest(method, uri, vertx, wrappedRoutingContexttHandler, new LocalHttpServerResponse(vertx));
    }
}

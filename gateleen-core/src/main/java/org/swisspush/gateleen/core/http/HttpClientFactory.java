package org.swisspush.gateleen.core.http;


import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;

import java.util.function.Function;


/**
 * Abstraction to create http clients.
 */
@FunctionalInterface
public interface HttpClientFactory extends Function<HttpClientOptions, HttpClient> {

    /**
     * @param opts
     *      Options to configure the http client.
     * @return
     *      The requested {@link HttpClient}.
     */
    HttpClient createHttpClient(HttpClientOptions opts);


    default HttpClient apply(HttpClientOptions opts) {
        return createHttpClient(opts);
    }

    static HttpClientFactory of(Vertx vertx) {
        return vertx::createHttpClient;
    }

}

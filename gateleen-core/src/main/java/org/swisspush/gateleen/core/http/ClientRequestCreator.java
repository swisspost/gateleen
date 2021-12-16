package org.swisspush.gateleen.core.http;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.impl.headers.VertxHttpHeaders;

/**
 * Wrapper for the {@link HttpClient} to create client requests.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class ClientRequestCreator {

    private final HttpClient selfClient;

    public ClientRequestCreator(final HttpClient selfClient){
        this.selfClient = selfClient;
    }

    public HttpClientRequest createClientRequest(HttpMethod method, String requestURI, MultiMap headers, long timeoutMs,
                                                 Handler<HttpClientResponse> responseHandler, Handler<Throwable> exceptionHandler){
        HttpClientRequest delegateRequest = selfClient.request(method, requestURI, responseHandler);
        delegateRequest.headers().setAll(headers);
        delegateRequest.exceptionHandler(exceptionHandler);
        delegateRequest.setTimeout(timeoutMs); // avoids blocking other requests
        return delegateRequest;
    }
}

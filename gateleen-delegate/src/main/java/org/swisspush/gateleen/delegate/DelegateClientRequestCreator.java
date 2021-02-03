package org.swisspush.gateleen.delegate;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.impl.headers.VertxHttpHeaders;

/**
 * Wrapper for the {@link HttpClient} to create delegate client requests.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class DelegateClientRequestCreator {

    private final HttpClient selfClient;

    public DelegateClientRequestCreator(final HttpClient selfClient){
        this.selfClient = selfClient;
    }

    public HttpClientRequest createClientRequest(HttpMethod method, String requestURI, VertxHttpHeaders headers, long timeoutMs,
                                                 Handler<HttpClientResponse> responseHandler, Handler<Throwable> exceptionHandler){
        HttpClientRequest delegateRequest = selfClient.request(method, requestURI, responseHandler);
        delegateRequest.headers().setAll(headers);
        delegateRequest.exceptionHandler(exceptionHandler);
        delegateRequest.setTimeout(timeoutMs); // avoids blocking other requests
        return delegateRequest;
    }
}

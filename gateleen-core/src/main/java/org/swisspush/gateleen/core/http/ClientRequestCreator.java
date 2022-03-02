package org.swisspush.gateleen.core.http;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.impl.headers.HeadersMultiMap;

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

    public Future<HttpClientRequest> createClientRequest(HttpMethod method, String requestURI, HeadersMultiMap headers, long timeoutMs,
                                                         Handler<Throwable> exceptionHandler) {
        Promise<HttpClientRequest> promise = Promise.promise();
        selfClient.request(method, requestURI).onComplete(asyncResult -> {
            HttpClientRequest delegateRequest = asyncResult.result();
            if (asyncResult.failed()) {
                promise.fail(asyncResult.cause());
                return;
            }
            delegateRequest.headers().setAll(headers);
            delegateRequest.exceptionHandler(exceptionHandler);
            delegateRequest.setTimeout(timeoutMs); // avoids blocking other requests
            promise.complete(delegateRequest);
        });
        return promise.future();
    }
}

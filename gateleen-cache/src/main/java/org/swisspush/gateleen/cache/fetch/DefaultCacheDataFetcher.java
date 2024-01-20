package org.swisspush.gateleen.cache.fetch;

import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.ClientRequestCreator;
import org.swisspush.gateleen.core.util.Result;
import org.swisspush.gateleen.core.util.StatusCode;

public class DefaultCacheDataFetcher implements CacheDataFetcher {

    private Logger log = LoggerFactory.getLogger(DefaultCacheDataFetcher.class);
    private final ClientRequestCreator clientRequestCreator;

    private static final String SELF_REQUEST_HEADER = "x-self-request";
    private static final String DEFAULT_CACHE_CONTROL_HEADER = "Cache-Control";
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String CONTENT_TYPE_JSON = "application/json";

    private final String cacheControlHeader;

    /**
     * Constructor for the {@link DefaultCacheDataFetcher} using the default `Cache-Control` request header
     *
     * @param clientRequestCreator the {@link ClientRequestCreator}
     */
    public DefaultCacheDataFetcher(ClientRequestCreator clientRequestCreator) {
        this(clientRequestCreator, DEFAULT_CACHE_CONTROL_HEADER);
    }

    /**
     * Constructor for the {@link DefaultCacheDataFetcher} using a custom request header
     *
     * @param clientRequestCreator     the {@link ClientRequestCreator}
     * @param customCacheControlHeader custom request header for cached requests instead of `Cache-Control`
     */
    public DefaultCacheDataFetcher(ClientRequestCreator clientRequestCreator, String customCacheControlHeader) {
        this.clientRequestCreator = clientRequestCreator;
        this.cacheControlHeader = customCacheControlHeader;
    }

    @Override
    public Future<Result<Buffer, StatusCode>> fetchData(final String requestUri, HeadersMultiMap requestHeaders, long requestTimeoutMs) {
        Promise<Result<Buffer, StatusCode>> promise = Promise.promise();

        requestHeaders.remove(cacheControlHeader);
        clientRequestCreator.createClientRequest(
                HttpMethod.GET,
                requestUri,
                requestHeaders,
                requestTimeoutMs, event -> {
                    log.warn("Got an error while fetching cache data", event);
                    promise.complete(Result.err(StatusCode.INTERNAL_SERVER_ERROR));
                }).onComplete(event -> {
            if (event.failed()) {
                log.warn("Failed request to {}", requestUri, event.cause());
                return;
            }
            HttpClientRequest cReq = event.result();
            cReq.idleTimeout(requestTimeoutMs);
            cReq.headers().setAll(requestHeaders);
            cReq.headers().set("Accept", "application/json");
            cReq.headers().set(SELF_REQUEST_HEADER, "true");
            cReq.setChunked(true);
            cReq.send(asyncResult -> {
                HttpClientResponse cRes = asyncResult.result();
                cRes.bodyHandler(data -> {
                    if (StatusCode.OK.getStatusCode() == cRes.statusCode()) {

                        String contentType = cRes.getHeader(CONTENT_TYPE_HEADER);
                        if (contentType != null && !contentType.contains(CONTENT_TYPE_JSON)) {
                            log.warn("Content-Type {} is not supported", contentType);
                            promise.complete(Result.err(StatusCode.UNSUPPORTED_MEDIA_TYPE));
                            return;
                        }

                        promise.complete(Result.ok(data));
                    } else {
                        StatusCode statusCode = StatusCode.fromCode(cRes.statusCode());
                        if (statusCode == null) {
                            log.error("Got unknown status code {} while fetching cache data. Using 500 Internal Server Error " +
                                    "instead", cRes.statusCode());
                            statusCode = StatusCode.INTERNAL_SERVER_ERROR;
                        }
                        promise.complete(Result.err(statusCode));
                    }
                });

                cRes.exceptionHandler(event1 -> {
                    log.warn("Got an error while fetching cache data", event1);
                    promise.complete(Result.err(StatusCode.INTERNAL_SERVER_ERROR));
                });
            });
        });

        return promise.future();
    }
}

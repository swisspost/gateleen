package org.swisspush.gateleen.cache.fetch;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.Result;
import org.swisspush.gateleen.core.util.StatusCode;

public class DefaultCacheDataFetcher implements CacheDataFetcher {

    private Logger log = LoggerFactory.getLogger(DefaultCacheDataFetcher.class);
    private HttpClient httpClient;

    private static final String SELF_REQUEST_HEADER = "x-self-request";
    private static final String CACHE_CONTROL_HEADER = "Cache-Control";
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String CONTENT_TYPE_JSON = "application/json";

    public DefaultCacheDataFetcher(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public Future<Result<Buffer, StatusCode>> fetchData(final String requestUri, MultiMap requestHeaders, long requestTimeoutMs) {
        Future<Result<Buffer, StatusCode>> future = Future.future();

        requestHeaders.remove(CACHE_CONTROL_HEADER);

        final HttpClientRequest cReq = httpClient.request(HttpMethod.GET, requestUri, cRes -> {

            cRes.bodyHandler(data -> {
                if (StatusCode.OK.getStatusCode() == cRes.statusCode()) {

                    String contentType = cRes.getHeader(CONTENT_TYPE_HEADER);
                    if(contentType != null && !contentType.contains(CONTENT_TYPE_JSON)){
                        log.warn("Content-Type {} is not supported", contentType);
                        future.complete(Result.err(StatusCode.UNSUPPORTED_MEDIA_TYPE));
                        return;
                    }

                    future.complete(Result.ok(data));
                } else {
                    StatusCode statusCode = StatusCode.fromCode(cRes.statusCode());
                    if(statusCode == null) {
                        log.error("Got unkown status code {} while fetching cache data. Using 500 Internal Server Error " +
                                "instead", cRes.statusCode());
                        statusCode = StatusCode.INTERNAL_SERVER_ERROR;
                    }
                    future.complete(Result.err(statusCode));
                }
            });

            cRes.exceptionHandler(event -> {
                log.warn("Got an error while fetching cache data", event);
                future.complete(Result.err(StatusCode.INTERNAL_SERVER_ERROR));
            });

        });

        cReq.setTimeout(requestTimeoutMs);
        cReq.headers().setAll(requestHeaders);
        cReq.headers().set("Accept", "application/json");
        cReq.headers().set(SELF_REQUEST_HEADER, "true");
        cReq.setChunked(true);
        cReq.end();

        return future;
    }
}

package org.swisspush.gateleen.cache;

import com.google.common.base.Splitter;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.cache.fetch.CacheDataFetcher;
import org.swisspush.gateleen.cache.storage.CacheStorage;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.Result;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.core.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Handler class dealing with cached responses.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class CacheHandler {

    public static final String CONTENT_TYPE_HEADER = "Content-Type";
    public static final String CONTENT_TYPE_JSON = "application/json";
    public static final String CACHE_CONTROL_HEADER = "Cache-Control";
    private static final String NO_CACHE = "no-cache";
    private static final String MAX_AGE = "max-age=";
    private static final String MAX_AGE_ZERO = MAX_AGE + "0";
    private static final int TIMEOUT = 30000;
    private final Logger log = LoggerFactory.getLogger(CacheHandler.class);

    private final CacheDataFetcher dataFetcher;
    private final CacheStorage cacheStorage;

    public CacheHandler(CacheDataFetcher dataFetcher, CacheStorage cacheStorage) {
        this.dataFetcher = dataFetcher;
        this.cacheStorage = cacheStorage;
    }

    public boolean handle(final HttpServerRequest request) {
        if (HttpMethod.GET != request.method() || !containsCacheHeaders(request)) {
            return false;
        }
        request.pause();

        log.debug("Got a request which may be be cached");
        Optional<Long> expireMs = extractExpireMs(request);
        if (expireMs.isEmpty()) {
            log.warn("Could not extract max-age value from Cache-Control request header");
            respondWith(StatusCode.BAD_REQUEST, request);
            return true;
        }

        String cacheIdentifier = request.uri();
        cacheStorage.cachedRequest(cacheIdentifier).setHandler(event -> {
            if(event.failed()){
                log.warn("Failed to get cached request from storage", event.cause());
                respondWith(StatusCode.INTERNAL_SERVER_ERROR, request);
                return;
            }

            Optional<JsonObject> cachedRequest = event.result();
            if(cachedRequest.isPresent()) {
                log.debug("Request to {} found in cache storage", request.uri());
                respondCachedRequest(request, cachedRequest.get());
            } else {
                updateCacheAndRespond(request, cacheIdentifier, expireMs.get());
            }
        });

        return true;
    }

    private void updateCacheAndRespond(final HttpServerRequest request, String cacheIdentifier, Long expireMs){
        log.debug("Request to {} not found in cache storage, going to fetch it.", request.uri());
        dataFetcher.fetchData(request.uri(), request.headers(), TIMEOUT).setHandler(event -> {
            if(event.failed()) {
                log.warn("Failed to fetch data from request", event.cause());
                respondWith(StatusCode.INTERNAL_SERVER_ERROR, request);
                return;
            }

            Result<JsonObject, StatusCode> result = event.result();
            if(result.isErr()) {
                respondWith(result.err(), request);
                return;
            }

            JsonObject fetchedData = result.ok();
            cacheStorage.cacheRequest(cacheIdentifier, fetchedData, Duration.ofMillis(expireMs)).setHandler(event1 -> {
                if (event1.failed()){
                    log.warn("Failed to store request to cache", event1.cause());
                }
                respondCachedRequest(request, fetchedData);
            });

        });
    }

    private boolean containsCacheHeaders(final HttpServerRequest request) {
        List<String> cacheControlHeaderValues = request.headers().getAll(CACHE_CONTROL_HEADER);
        for (String cacheControlHeaderValue : cacheControlHeaderValues) {
            if (NO_CACHE.equalsIgnoreCase(cacheControlHeaderValue) ||
                    cacheControlHeaderValue.toLowerCase().contains(MAX_AGE_ZERO)) {
                return false;
            }
            if (cacheControlHeaderValue.toLowerCase().contains(MAX_AGE)) {
                return true;
            }
        }
        return false;
    }

    private Optional<Long> extractExpireMs(final HttpServerRequest request) {
        String cacheControlHeader = request.headers().get(CACHE_CONTROL_HEADER);
        if (cacheControlHeader == null || !cacheControlHeader.toLowerCase().contains(MAX_AGE)) {
            return Optional.empty();
        }

        cacheControlHeader = StringUtils.trim(cacheControlHeader).toLowerCase();
        List<String> headerValues = Splitter.on(MAX_AGE).omitEmptyStrings().splitToList(cacheControlHeader);
        if (headerValues.size() != 1) {
            return Optional.empty();
        }

        String headerValue = headerValues.get(0);
        try {
            Long expireSeconds = Long.valueOf(headerValue);
            return Optional.of(expireSeconds * 1000);
        } catch (NumberFormatException ex) {
            log.warn("Value of Cache-Control max-age header is not a number: {}", headerValue);
            return Optional.empty();
        }
    }

    private void respondWith(StatusCode statusCode, final HttpServerRequest request) {
        ResponseStatusCodeLogUtil.info(request, statusCode, CacheHandler.class);
        request.response().setStatusCode(statusCode.getStatusCode());
        request.response().setStatusMessage(statusCode.getStatusMessage());
        request.response().end();
        request.resume();
    }

    private void respondCachedRequest(final HttpServerRequest request, JsonObject cachedRequestPayload) {
        ResponseStatusCodeLogUtil.info(request, StatusCode.OK, CacheHandler.class);
        request.response().setStatusCode(StatusCode.OK.getStatusCode());
        request.response().setStatusMessage(StatusCode.OK.getStatusMessage());
        request.response().headers().add(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON);
        request.response().end(cachedRequestPayload.encode());
        request.resume();
    }
}

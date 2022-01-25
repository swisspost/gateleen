package org.swisspush.gateleen.cache;

import com.google.common.base.Splitter;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
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
import java.util.Set;

/**
 * Handler class dealing with cached responses.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class CacheHandler {

    public static final String CONTENT_TYPE_HEADER = "Content-Type";
    public static final String CONTENT_TYPE_JSON = "application/json";
    public static final String DEFAULT_CACHE_CONTROL_HEADER = "Cache-Control";
    private static final String NO_CACHE = "no-cache";
    private static final String MAX_AGE = "max-age=";
    private static final String MAX_AGE_ZERO = MAX_AGE + "0";
    private static final int TIMEOUT_MS = 30000;
    private final Logger log = LoggerFactory.getLogger(CacheHandler.class);

    private final CacheDataFetcher dataFetcher;
    private final CacheStorage cacheStorage;
    private final String cacheAdminUri;

    private final String cacheControlHeader;

    /**
     * Constructor for the {@link CacheHandler} using the default `Cache-Control` request header
     *
     * @param dataFetcher the {@link CacheDataFetcher}
     * @param cacheStorage the {@link CacheStorage}
     * @param cacheAdminUri the uri for the admin API
     */
    public CacheHandler(CacheDataFetcher dataFetcher, CacheStorage cacheStorage, String cacheAdminUri) {
        this(dataFetcher, cacheStorage, cacheAdminUri, DEFAULT_CACHE_CONTROL_HEADER);
    }

    /**
     * Constructor for the {@link CacheHandler} using a custom request header
     *
     * @param dataFetcher the {@link CacheDataFetcher}
     * @param cacheStorage the {@link CacheStorage}
     * @param cacheAdminUri the uri for the admin API
     * @param customCacheControlHeader custom request header for cached requests instead of `Cache-Control`
     */
    public CacheHandler(CacheDataFetcher dataFetcher, CacheStorage cacheStorage, String cacheAdminUri, String customCacheControlHeader) {
        this.dataFetcher = dataFetcher;
        this.cacheStorage = cacheStorage;
        this.cacheAdminUri = cacheAdminUri;
        this.cacheControlHeader = customCacheControlHeader;
    }

    public boolean handle(final HttpServerRequest request) {
        if (request.uri().startsWith(cacheAdminUri)) {
            if(HttpMethod.POST == request.method() && request.uri().equals(cacheAdminUri + "/clear")) {
                handleClearCache(request);
            } else if (HttpMethod.GET == request.method() && request.uri().equals(cacheAdminUri + "/count")) {
                handleCacheCount(request);
            } else if (HttpMethod.GET == request.method() && request.uri().equals(cacheAdminUri + "/entries")) {
                handleCacheEntries(request);
            } else {
                respondWith(StatusCode.METHOD_NOT_ALLOWED, request);
            }
            return true;
        }
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

            Optional<Buffer> cachedRequest = event.result();
            if(cachedRequest.isPresent()) {
                log.debug("Request to {} found in cache storage", request.uri());
                respondWithPayload(request, cachedRequest.get());
            } else {
                updateCacheAndRespond(request, cacheIdentifier, expireMs.get());
            }
        });

        return true;
    }

    private void updateCacheAndRespond(final HttpServerRequest request, String cacheIdentifier, Long expireMs){
        log.debug("Request to {} not found in cache storage, going to fetch it.", request.uri());
        dataFetcher.fetchData(request.uri(), request.headers(), TIMEOUT_MS).setHandler(event -> {
            if(event.failed()) {
                log.warn("Failed to fetch data from request", event.cause());
                respondWith(StatusCode.INTERNAL_SERVER_ERROR, request);
                return;
            }

            Result<Buffer, StatusCode> result = event.result();
            if(result.isErr()) {
                respondWith(result.err(), request);
                return;
            }

            Buffer fetchedData = result.ok();
            cacheStorage.cacheRequest(cacheIdentifier, fetchedData, Duration.ofMillis(expireMs)).setHandler(event1 -> {
                if (event1.failed()){
                    log.warn("Failed to store request to cache", event1.cause());
                }
                respondWithPayload(request, fetchedData);
            });

        });
    }

    private boolean containsCacheHeaders(final HttpServerRequest request) {
        List<String> cacheControlHeaderValues = request.headers().getAll(cacheControlHeader);
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
        String cacheControlHeaderValue = request.headers().get(cacheControlHeader);
        if (cacheControlHeaderValue == null || !cacheControlHeaderValue.toLowerCase().contains(MAX_AGE)) {
            return Optional.empty();
        }

        cacheControlHeaderValue = StringUtils.trim(cacheControlHeaderValue).toLowerCase();
        List<String> headerValues = Splitter.on(MAX_AGE).omitEmptyStrings().splitToList(cacheControlHeaderValue);
        if (headerValues.size() != 1) {
            return Optional.empty();
        }

        String headerValue = headerValues.get(0);
        try {
            long expireSeconds = Long.parseLong(headerValue);
            return Optional.of(expireSeconds * 1000);
        } catch (NumberFormatException ex) {
            log.warn("Value of {} max-age header is not a number: {}", cacheControlHeader, headerValue);
            return Optional.empty();
        }
    }

    private void handleClearCache(final HttpServerRequest request) {
        log.debug("About to clear all cached entries manually");
        cacheStorage.clearCache().setHandler(event -> {
            if(event.failed()) {
                log.warn("Error while clearing cache", event.cause());
                respondWith(StatusCode.INTERNAL_SERVER_ERROR, request);
            }
            Long clearedCount = event.result();
            log.debug("Cleared {} cache entries", clearedCount);
            JsonObject clearedObj = new JsonObject().put("cleared", clearedCount);
            respondWithPayload(request, Buffer.buffer(clearedObj.encode()));
        });
    }

    private void handleCacheEntries(final HttpServerRequest request) {
        log.debug("About to get cached entries list");
        cacheStorage.cacheEntries().setHandler(event -> {
            if(event.failed()) {
                log.warn("Error while getting cached entries list", event.cause());
                respondWith(StatusCode.INTERNAL_SERVER_ERROR, request);
            }
            Set<String> cachedEntries = event.result();
            log.debug("{} entries in cache", cachedEntries.size());
            JsonArray entriesArray = new JsonArray();
            for (String cachedEntry : cachedEntries) {
                entriesArray.add(cachedEntry);
            }
            JsonObject entriesObj = new JsonObject().put("entries", entriesArray);
            respondWithPayload(request, Buffer.buffer(entriesObj.encode()));
        });
    }

    private void handleCacheCount(final HttpServerRequest request) {
        log.debug("About to get cached entries count");
        cacheStorage.cacheEntriesCount().setHandler(event -> {
            if(event.failed()) {
                log.warn("Error while getting cached entries count", event.cause());
                respondWith(StatusCode.INTERNAL_SERVER_ERROR, request);
            }
            Long count = event.result();
            log.debug("{} entries in cache", count);
            JsonObject clearedObj = new JsonObject().put("count", count);
            respondWithPayload(request, Buffer.buffer(clearedObj.encode()));
        });
    }

    private void respondWith(StatusCode statusCode, final HttpServerRequest request) {
        ResponseStatusCodeLogUtil.info(request, statusCode, CacheHandler.class);
        request.response().setStatusCode(statusCode.getStatusCode());
        request.response().setStatusMessage(statusCode.getStatusMessage());
        request.response().end();
        request.resume();
    }

    private void respondWithPayload(final HttpServerRequest request, Buffer cachedRequestPayload) {
        ResponseStatusCodeLogUtil.info(request, StatusCode.OK, CacheHandler.class);
        request.response().setStatusCode(StatusCode.OK.getStatusCode());
        request.response().setStatusMessage(StatusCode.OK.getStatusMessage());
        request.response().headers().add(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON);
        request.response().end(cachedRequestPayload);
        request.resume();
    }
}

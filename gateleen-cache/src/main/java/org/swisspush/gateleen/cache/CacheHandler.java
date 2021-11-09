package org.swisspush.gateleen.cache;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Handler class dealing with cached responses.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class CacheHandler {

    private static final String CACHE_CONTROL_HEADER = "Cache-Control";
    private static final String NO_CACHE = "no-cache";
    private static final String MAX_AGE = "max-age";
    private final Logger log = LoggerFactory.getLogger(CacheHandler.class);

    private boolean containsCacheHeaders(final HttpServerRequest request){
        List<String> cacheControlHeaderValues = request.headers().getAll(CACHE_CONTROL_HEADER);
        for (String cacheControlHeaderValue : cacheControlHeaderValues) {
            if(NO_CACHE.equalsIgnoreCase(cacheControlHeaderValue)) {
                return false;
            }
            if(cacheControlHeaderValue.toLowerCase().contains(MAX_AGE)){
                return true;
            }
        }
        return false;
    }

    public boolean handle(final HttpServerRequest request) {
        if(HttpMethod.GET == request.method() && containsCacheHeaders(request)) {
            log.debug("Got a request which could be cached");

            //TODO handle response from cache

            return true;
        }
        return false;
    }
}

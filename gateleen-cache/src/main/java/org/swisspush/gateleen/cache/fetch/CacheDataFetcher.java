package org.swisspush.gateleen.cache.fetch;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import org.swisspush.gateleen.core.util.Result;
import org.swisspush.gateleen.core.util.StatusCode;

public interface CacheDataFetcher {

    Future<Result<JsonObject, StatusCode>> fetchData(final String requestUri, MultiMap requestHeaders, long requestTimeoutMs);
}

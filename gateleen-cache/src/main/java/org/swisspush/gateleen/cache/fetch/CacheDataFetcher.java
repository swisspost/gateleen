package org.swisspush.gateleen.cache.fetch;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import org.swisspush.gateleen.core.util.Result;
import org.swisspush.gateleen.core.util.StatusCode;

public interface CacheDataFetcher {

    Future<Result<Buffer, StatusCode>> fetchData(final String requestUri, HeadersMultiMap requestHeaders, long requestTimeoutMs);
}

package org.swisspush.gateleen.cache;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.swisspush.gateleen.cache.fetch.CacheDataFetcher;
import org.swisspush.gateleen.cache.storage.CacheStorage;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.http.DummyHttpServerResponse;
import org.swisspush.gateleen.core.util.Result;
import org.swisspush.gateleen.core.util.StatusCode;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import static org.mockito.Mockito.*;
import static org.swisspush.gateleen.cache.CacheHandler.*;

@RunWith(VertxUnitRunner.class)
public class CacheHandlerTest {

    private CacheHandler cacheHandler;
    private CacheDataFetcher dataFetcher = mock(CacheDataFetcher.class);
    private CacheStorage cacheStorage = mock(CacheStorage.class);

    private Buffer bufferFromJson(JsonObject jsonObject) {
        return Buffer.buffer(jsonObject.encode());
    }

    @Before
    public void setUp() {
        cacheHandler = new CacheHandler(dataFetcher, cacheStorage, "/playground/server/cache");
    }

    @Test
    public void testOnlyGETRequestsWithCacheControlHeadersAreHandled(TestContext context) {
        when(cacheStorage.cachedRequest(anyString())).thenReturn(Future.succeededFuture(Optional.of(bufferFromJson(new JsonObject()))));
        HttpServerResponse response = spy(new Response());

        Request putRequest = new Request(HttpMethod.PUT, "/some/path", MultiMap.caseInsensitiveMultiMap(), response);
        context.assertFalse(cacheHandler.handle(putRequest));

        Request getRequestNoHeaders = new Request(HttpMethod.GET, "/some/path", MultiMap.caseInsensitiveMultiMap(), response);
        context.assertFalse(cacheHandler.handle(getRequestNoHeaders));

        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("foo", "bar");
        Request getRequestOtherHeaders = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertFalse(cacheHandler.handle(getRequestOtherHeaders));

        headers = MultiMap.caseInsensitiveMultiMap();
        headers.add(DEFAULT_CACHE_CONTROL_HEADER, "max-age=120");
        Request getRequestWithCacheControlHeaders = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertTrue(cacheHandler.handle(getRequestWithCacheControlHeaders));

        // custom cache control header
        cacheHandler = new CacheHandler(dataFetcher, cacheStorage, "/playground/server/cache", "x-cache-control");
        headers = new HeadersMultiMap();
        headers.add("x-cache-control", "max-age=120");
        Request getRequestWithCustomCacheControlHeaders = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertTrue(cacheHandler.handle(getRequestWithCustomCacheControlHeaders));
    }

    @Test
    public void testNotSupportedCacheControlHeadersAreNotHandled(TestContext context) {
        HttpServerResponse response = spy(new Response());

        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add(DEFAULT_CACHE_CONTROL_HEADER, "private");
        Request cacheControlPrivate = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertFalse(cacheHandler.handle(cacheControlPrivate));

        headers = MultiMap.caseInsensitiveMultiMap();
        headers.add(DEFAULT_CACHE_CONTROL_HEADER, "public");
        Request cacheControlPublic = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertFalse(cacheHandler.handle(cacheControlPublic));

        headers = MultiMap.caseInsensitiveMultiMap();
        headers.add(DEFAULT_CACHE_CONTROL_HEADER, "no-cache");
        Request cacheControlNoCache = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertFalse(cacheHandler.handle(cacheControlNoCache));

        headers = MultiMap.caseInsensitiveMultiMap();
        headers.add(DEFAULT_CACHE_CONTROL_HEADER, "max-age=0");
        Request cacheControlMaxAgeZero = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertFalse(cacheHandler.handle(cacheControlMaxAgeZero));

        Mockito.verifyZeroInteractions(response);
    }

    @Test
    public void testInvalidCacheControlHeader(TestContext context) {
        HttpServerResponse response = spy(new Response());

        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add(DEFAULT_CACHE_CONTROL_HEADER, "max-age=foobar");
        Request getRequestWithCacheControlHeaders = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertTrue(cacheHandler.handle(getRequestWithCacheControlHeaders));

        verify(response, times(1)).setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
        verify(response, times(1)).setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
    }

    @Test
    public void testCachedRequestFromCacheStorage(TestContext context) {
        Buffer dataObj = bufferFromJson(new JsonObject().put("foo", "bar"));
        HttpServerResponse response = spy(new Response());
        when(cacheStorage.cachedRequest(anyString())).thenReturn(Future.succeededFuture(Optional.of(dataObj)));

        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add(DEFAULT_CACHE_CONTROL_HEADER, "max-age=120");
        Request getRequestWithCacheControlHeaders = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertTrue(cacheHandler.handle(getRequestWithCacheControlHeaders));

        verify(response, times(1)).setStatusCode(StatusCode.OK.getStatusCode());
        verify(response, times(1)).setStatusMessage(StatusCode.OK.getStatusMessage());
        verify(response, timeout(1000).times(1)).end(eq(dataObj));
    }

    @Test
    public void testCachedRequestFromCacheStorageFail(TestContext context) {
        HttpServerResponse response = spy(new Response());
        when(cacheStorage.cachedRequest(anyString())).thenReturn(Future.failedFuture("Boooom"));

        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add(DEFAULT_CACHE_CONTROL_HEADER, "max-age=120");
        Request getRequestWithCacheControlHeaders = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertTrue(cacheHandler.handle(getRequestWithCacheControlHeaders));

        verify(response, times(1)).setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
        verify(response, times(1)).setStatusMessage(StatusCode.INTERNAL_SERVER_ERROR.getStatusMessage());
        verify(response, timeout(1000).times(1)).end();
    }

    @Test
    public void testCachedRequestFromDataFetcherFail(TestContext context) {
        HttpServerResponse response = spy(new Response());
        when(cacheStorage.cachedRequest(anyString())).thenReturn(Future.succeededFuture(Optional.empty()));
        when(dataFetcher.fetchData(anyString(), any(), anyLong())).thenReturn(Future.failedFuture("Booom"));

        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add(DEFAULT_CACHE_CONTROL_HEADER, "max-age=120");
        Request getRequestWithCacheControlHeaders = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertTrue(cacheHandler.handle(getRequestWithCacheControlHeaders));

        verify(cacheStorage, times(1)).cachedRequest(eq(getRequestWithCacheControlHeaders.uri));
        verify(cacheStorage, never()).cacheRequest(anyString(), any(), any());
        verify(response, times(1)).setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
        verify(response, times(1)).setStatusMessage(StatusCode.INTERNAL_SERVER_ERROR.getStatusMessage());
        verify(response, timeout(1000).times(1)).end();
    }

    @Test
    public void testCachedRequestFromDataFetcherNotFound(TestContext context) {
        HttpServerResponse response = spy(new Response());
        when(cacheStorage.cachedRequest(anyString())).thenReturn(Future.succeededFuture(Optional.empty()));
        when(dataFetcher.fetchData(anyString(), any(), anyLong())).thenReturn(Future.succeededFuture(Result.err(StatusCode.NOT_FOUND)));

        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add(DEFAULT_CACHE_CONTROL_HEADER, "max-age=120");
        Request getRequestWithCacheControlHeaders = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertTrue(cacheHandler.handle(getRequestWithCacheControlHeaders));

        verify(cacheStorage, times(1)).cachedRequest(eq(getRequestWithCacheControlHeaders.uri));
        verify(cacheStorage, never()).cacheRequest(anyString(), any(), any());
        verify(response, times(1)).setStatusCode(StatusCode.NOT_FOUND.getStatusCode());
        verify(response, times(1)).setStatusMessage(StatusCode.NOT_FOUND.getStatusMessage());
        verify(response, timeout(1000).times(1)).end();
    }

    @Test
    public void testCachedRequestFromDataFetcherOKButCacheToStorageFails(TestContext context) {
        Buffer dataObj = bufferFromJson(new JsonObject().put("foo", "bar"));
        HttpServerResponse response = spy(new Response());
        when(cacheStorage.cachedRequest(anyString())).thenReturn(Future.succeededFuture(Optional.empty()));
        when(dataFetcher.fetchData(anyString(), any(), anyLong())).thenReturn(Future.succeededFuture(Result.ok(dataObj)));
        when(cacheStorage.cacheRequest(anyString(), any(), any())).thenReturn(Future.failedFuture("Booom"));

        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add(DEFAULT_CACHE_CONTROL_HEADER, "max-age=120");
        Request getRequestWithCacheControlHeaders = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertTrue(cacheHandler.handle(getRequestWithCacheControlHeaders));

        verify(cacheStorage, times(1)).cachedRequest(eq(getRequestWithCacheControlHeaders.uri));
        verify(cacheStorage, times(1)).cacheRequest(eq(getRequestWithCacheControlHeaders.uri), eq(dataObj), any());
        verify(response, times(1)).setStatusCode(StatusCode.OK.getStatusCode());
        verify(response, times(1)).setStatusMessage(StatusCode.OK.getStatusMessage());
        verify(response, timeout(1000).times(1)).end(dataObj);
    }

    @Test
    public void testCachedRequestFromDataFetcherOKAndCacheToStorageWriteOK(TestContext context) {
        Buffer dataObj = bufferFromJson(new JsonObject().put("foo", "bar"));
        HttpServerResponse response = spy(new Response());
        when(cacheStorage.cachedRequest(anyString())).thenReturn(Future.succeededFuture(Optional.empty()));
        when(dataFetcher.fetchData(anyString(), any(), anyLong())).thenReturn(Future.succeededFuture(Result.ok(dataObj)));
        when(cacheStorage.cacheRequest(anyString(), any(), any())).thenReturn(Future.succeededFuture());

        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add(DEFAULT_CACHE_CONTROL_HEADER, "max-age=120");
        Request getRequestWithCacheControlHeaders = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertTrue(cacheHandler.handle(getRequestWithCacheControlHeaders));

        verify(cacheStorage, times(1)).cachedRequest(eq(getRequestWithCacheControlHeaders.uri));
        verify(cacheStorage, times(1)).cacheRequest(eq(getRequestWithCacheControlHeaders.uri), eq(dataObj), any());
        verify(response, times(1)).setStatusCode(StatusCode.OK.getStatusCode());
        verify(response, times(1)).setStatusMessage(StatusCode.OK.getStatusMessage());
        verify(response, timeout(1000).times(1)).end(dataObj);
        context.assertEquals(CONTENT_TYPE_JSON, response.headers().get(CONTENT_TYPE_HEADER));
    }

    @Test
    public void testCacheAdminFunctionNotSupported(TestContext context) {
        HttpServerResponse response = spy(new Response());

        Request getRequestWithCacheControlHeaders = new Request(HttpMethod.GET, "/playground/server/cache/foobar", MultiMap.caseInsensitiveMultiMap(), response);
        context.assertTrue(cacheHandler.handle(getRequestWithCacheControlHeaders));

        verify(response, times(1)).setStatusCode(StatusCode.METHOD_NOT_ALLOWED.getStatusCode());
        verify(response, times(1)).setStatusMessage(StatusCode.METHOD_NOT_ALLOWED.getStatusMessage());
        verify(response, timeout(1000).times(1)).end();
    }

    @Test
    public void testCacheAdminFunctionClearCache(TestContext context) {
        HttpServerResponse response = spy(new Response());
        when(cacheStorage.clearCache()).thenReturn(Future.succeededFuture(99L));

        Request getRequestWithCacheControlHeaders = new Request(HttpMethod.POST, "/playground/server/cache/clear", MultiMap.caseInsensitiveMultiMap(), response);
        context.assertTrue(cacheHandler.handle(getRequestWithCacheControlHeaders));

        verify(response, times(1)).setStatusCode(StatusCode.OK.getStatusCode());
        verify(response, times(1)).setStatusMessage(StatusCode.OK.getStatusMessage());
        verify(response, timeout(1000).times(1)).end(bufferFromJson(new JsonObject().put("cleared", 99L)));
        context.assertEquals(CONTENT_TYPE_JSON, response.headers().get(CONTENT_TYPE_HEADER));
    }

    @Test
    public void testCacheAdminFunctionEntriesCount(TestContext context) {
        HttpServerResponse response = spy(new Response());
        when(cacheStorage.cacheEntriesCount()).thenReturn(Future.succeededFuture(15L));

        Request getRequestWithCacheControlHeaders = new Request(HttpMethod.GET, "/playground/server/cache/count", MultiMap.caseInsensitiveMultiMap(), response);
        context.assertTrue(cacheHandler.handle(getRequestWithCacheControlHeaders));

        verify(response, times(1)).setStatusCode(StatusCode.OK.getStatusCode());
        verify(response, times(1)).setStatusMessage(StatusCode.OK.getStatusMessage());
        verify(response, timeout(1000).times(1)).end(bufferFromJson(new JsonObject().put("count", 15L)));
        context.assertEquals(CONTENT_TYPE_JSON, response.headers().get(CONTENT_TYPE_HEADER));
    }

    @Test
    public void testCacheAdminFunctionEntries(TestContext context) {
        HttpServerResponse response = spy(new Response());
        when(cacheStorage.cacheEntries()).thenReturn(Future.succeededFuture(Set.of("/cached/res/1", "/cached/res/2", "/cached/res/3")));

        Request getRequestWithCacheControlHeaders = new Request(HttpMethod.GET, "/playground/server/cache/entries", MultiMap.caseInsensitiveMultiMap(), response);
        context.assertTrue(cacheHandler.handle(getRequestWithCacheControlHeaders));

        verify(response, times(1)).setStatusCode(StatusCode.OK.getStatusCode());
        verify(response, times(1)).setStatusMessage(StatusCode.OK.getStatusMessage());

        ArgumentCaptor<Buffer> argumentCaptor = ArgumentCaptor.forClass(Buffer.class);
        verify(response, timeout(1000).times(1)).end(argumentCaptor.capture());

        JsonArray entries = new JsonObject(argumentCaptor.getValue()).getJsonArray("entries");
        context.assertEquals(3, entries.size());
        context.assertTrue(entries.contains("/cached/res/1"));
        context.assertTrue(entries.contains("/cached/res/2"));
        context.assertTrue(entries.contains("/cached/res/3"));

        context.assertEquals(CONTENT_TYPE_JSON, response.headers().get(CONTENT_TYPE_HEADER));
    }

    @Test
    public void testCacheAdminFunctionEntriesEmpty(TestContext context) {
        HttpServerResponse response = spy(new Response());
        when(cacheStorage.cacheEntries()).thenReturn(Future.succeededFuture(Collections.emptySet()));

        Request getRequestWithCacheControlHeaders = new Request(HttpMethod.GET, "/playground/server/cache/entries", MultiMap.caseInsensitiveMultiMap(), response);
        context.assertTrue(cacheHandler.handle(getRequestWithCacheControlHeaders));

        verify(response, times(1)).setStatusCode(StatusCode.OK.getStatusCode());
        verify(response, times(1)).setStatusMessage(StatusCode.OK.getStatusMessage());
        verify(response, timeout(1000).times(1)).end(bufferFromJson(new JsonObject().put("entries", new JsonArray())));
        context.assertEquals(CONTENT_TYPE_JSON, response.headers().get(CONTENT_TYPE_HEADER));
    }

    private static class Request extends DummyHttpServerRequest {
        private MultiMap headers;
        private HttpMethod httpMethod;
        private String uri;
        private HttpServerResponse response;

        public Request(HttpMethod httpMethod, String uri, MultiMap headers, HttpServerResponse response) {
            this.httpMethod = httpMethod;
            this.uri = uri;
            this.headers = headers;
            this.response = response;
        }

        @Override public HttpMethod method() {
            return httpMethod;
        }
        @Override public String uri() {
            return uri;
        }
        @Override public HttpServerResponse response() {return response; }

        @Override
        public MultiMap headers() { return headers; }

        @Override
        public HttpServerRequest pause() { return this; }

        @Override
        public HttpServerRequest resume() { return this; }
    }

    private static class Response extends DummyHttpServerResponse {
        private MultiMap headers;

        public Response() {
            this.headers = MultiMap.caseInsensitiveMultiMap();
        }

        @Override
        public MultiMap headers() { return headers; }
    }
}

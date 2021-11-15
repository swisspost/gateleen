package org.swisspush.gateleen.cache;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.cache.fetch.CacheDataFetcher;
import org.swisspush.gateleen.cache.storage.CacheStorage;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.http.DummyHttpServerResponse;
import org.swisspush.gateleen.core.util.Result;
import org.swisspush.gateleen.core.util.StatusCode;

import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.swisspush.gateleen.cache.CacheHandler.*;

@RunWith(VertxUnitRunner.class)
public class CacheHandlerTest {

    private CacheHandler cacheHandler;
    private CacheDataFetcher dataFetcher = mock(CacheDataFetcher.class);
    private CacheStorage cacheStorage = mock(CacheStorage.class);

    @Before
    public void setUp() {
        cacheHandler = new CacheHandler(dataFetcher, cacheStorage);
    }

    @Test
    public void testOnlyGETRequestsWithCacheControlHeadersAreHandled(TestContext context) {
        when(cacheStorage.cachedRequest(anyString())).thenReturn(Future.succeededFuture(Optional.of(new JsonObject())));
        HttpServerResponse response = spy(new Response());

        Request putRequest = new Request(HttpMethod.PUT, "/some/path", new CaseInsensitiveHeaders(), response);
        context.assertFalse(cacheHandler.handle(putRequest));

        Request getRequestNoHeaders = new Request(HttpMethod.GET, "/some/path", new CaseInsensitiveHeaders(), response);
        context.assertFalse(cacheHandler.handle(getRequestNoHeaders));

        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();
        headers.add("foo", "bar");
        Request getRequestOtherHeaders = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertFalse(cacheHandler.handle(getRequestOtherHeaders));

        headers = new CaseInsensitiveHeaders();
        headers.add(CACHE_CONTROL_HEADER, "max-age=120");
        Request getRequestWithCacheControlHeaders = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertTrue(cacheHandler.handle(getRequestWithCacheControlHeaders));
    }

    @Test
    public void testNotSupportedCacheControlHeadersAreNotHandled(TestContext context) {
        HttpServerResponse response = spy(new Response());

        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();
        headers.add(CACHE_CONTROL_HEADER, "private");
        Request cacheControlPrivate = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertFalse(cacheHandler.handle(cacheControlPrivate));

        headers = new CaseInsensitiveHeaders();
        headers.add(CACHE_CONTROL_HEADER, "public");
        Request cacheControlPublic = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertFalse(cacheHandler.handle(cacheControlPublic));

        headers = new CaseInsensitiveHeaders();
        headers.add(CACHE_CONTROL_HEADER, "no-cache");
        Request cacheControlNoCache = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertFalse(cacheHandler.handle(cacheControlNoCache));

        headers = new CaseInsensitiveHeaders();
        headers.add(CACHE_CONTROL_HEADER, "max-age=0");
        Request cacheControlMaxAgeZero = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertFalse(cacheHandler.handle(cacheControlMaxAgeZero));

        Mockito.verifyZeroInteractions(response);
    }

    @Test
    public void testInvalidCacheControlHeader(TestContext context) {
        HttpServerResponse response = spy(new Response());

        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();
        headers.add(CACHE_CONTROL_HEADER, "max-age=foobar");
        Request getRequestWithCacheControlHeaders = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertTrue(cacheHandler.handle(getRequestWithCacheControlHeaders));

        verify(response, times(1)).setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
        verify(response, times(1)).setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
    }

    @Test
    public void testCachedRequestFromCacheStorage(TestContext context) {
        JsonObject dataObj = new JsonObject().put("foo", "bar");
        HttpServerResponse response = spy(new Response());
        when(cacheStorage.cachedRequest(anyString())).thenReturn(Future.succeededFuture(Optional.of(dataObj)));

        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();
        headers.add(CACHE_CONTROL_HEADER, "max-age=120");
        Request getRequestWithCacheControlHeaders = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertTrue(cacheHandler.handle(getRequestWithCacheControlHeaders));

        verify(response, times(1)).setStatusCode(StatusCode.OK.getStatusCode());
        verify(response, times(1)).setStatusMessage(StatusCode.OK.getStatusMessage());
        verify(response, timeout(1000).times(1)).end(eq(dataObj.encode()));
    }

    @Test
    public void testCachedRequestFromCacheStorageFail(TestContext context) {
        HttpServerResponse response = spy(new Response());
        when(cacheStorage.cachedRequest(anyString())).thenReturn(Future.failedFuture("Boooom"));

        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();
        headers.add(CACHE_CONTROL_HEADER, "max-age=120");
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

        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();
        headers.add(CACHE_CONTROL_HEADER, "max-age=120");
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

        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();
        headers.add(CACHE_CONTROL_HEADER, "max-age=120");
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
        JsonObject dataObj = new JsonObject().put("foo", "bar");
        HttpServerResponse response = spy(new Response());
        when(cacheStorage.cachedRequest(anyString())).thenReturn(Future.succeededFuture(Optional.empty()));
        when(dataFetcher.fetchData(anyString(), any(), anyLong())).thenReturn(Future.succeededFuture(Result.ok(dataObj)));
        when(cacheStorage.cacheRequest(anyString(), any(), any())).thenReturn(Future.failedFuture("Booom"));

        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();
        headers.add(CACHE_CONTROL_HEADER, "max-age=120");
        Request getRequestWithCacheControlHeaders = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertTrue(cacheHandler.handle(getRequestWithCacheControlHeaders));

        verify(cacheStorage, times(1)).cachedRequest(eq(getRequestWithCacheControlHeaders.uri));
        verify(cacheStorage, times(1)).cacheRequest(eq(getRequestWithCacheControlHeaders.uri), eq(dataObj), any());
        verify(response, times(1)).setStatusCode(StatusCode.OK.getStatusCode());
        verify(response, times(1)).setStatusMessage(StatusCode.OK.getStatusMessage());
        verify(response, timeout(1000).times(1)).end(dataObj.encode());
    }

    @Test
    public void testCachedRequestFromDataFetcherOKAndCacheToStorageWriteOK(TestContext context) {
        JsonObject dataObj = new JsonObject().put("foo", "bar");
        HttpServerResponse response = spy(new Response());
        when(cacheStorage.cachedRequest(anyString())).thenReturn(Future.succeededFuture(Optional.empty()));
        when(dataFetcher.fetchData(anyString(), any(), anyLong())).thenReturn(Future.succeededFuture(Result.ok(dataObj)));
        when(cacheStorage.cacheRequest(anyString(), any(), any())).thenReturn(Future.succeededFuture());

        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();
        headers.add(CACHE_CONTROL_HEADER, "max-age=120");
        Request getRequestWithCacheControlHeaders = new Request(HttpMethod.GET, "/some/path", headers, response);
        context.assertTrue(cacheHandler.handle(getRequestWithCacheControlHeaders));

        verify(cacheStorage, times(1)).cachedRequest(eq(getRequestWithCacheControlHeaders.uri));
        verify(cacheStorage, times(1)).cacheRequest(eq(getRequestWithCacheControlHeaders.uri), eq(dataObj), any());
        verify(response, times(1)).setStatusCode(StatusCode.OK.getStatusCode());
        verify(response, times(1)).setStatusMessage(StatusCode.OK.getStatusMessage());
        verify(response, timeout(1000).times(1)).end(dataObj.encode());
        context.assertEquals(CONTENT_TYPE_JSON, response.headers().get(CONTENT_TYPE_HEADER));
    }


    private class Request extends DummyHttpServerRequest {
        private CaseInsensitiveHeaders headers;
        private HttpMethod httpMethod;
        private String uri;
        private HttpServerResponse response;

        public Request(HttpMethod httpMethod, String uri, CaseInsensitiveHeaders headers, HttpServerResponse response) {
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

    private class Response extends DummyHttpServerResponse {
        private CaseInsensitiveHeaders headers;

        public Response() {
            this.headers = new CaseInsensitiveHeaders();
        }

        @Override
        public MultiMap headers() { return headers; }
    }
}

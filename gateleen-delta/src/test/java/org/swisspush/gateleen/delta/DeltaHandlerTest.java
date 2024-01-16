package org.swisspush.gateleen.delta;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.redis.client.RedisAPI;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.http.DummyHttpServerResponse;
import org.swisspush.gateleen.core.redis.RedisProvider;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.routing.Router;
import org.swisspush.gateleen.routing.Rule;
import org.swisspush.gateleen.routing.RuleProvider;

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@RunWith(VertxUnitRunner.class)
public class DeltaHandlerTest {

    private RedisAPI redisAPI;
    private RedisProvider redisProvider;
    private RuleProvider ruleProvider;
    private Router router = mock(Router.class);
    private HttpServerRequest request;
    private HttpServerResponse response;
    private MultiMap requestHeaders = MultiMap.caseInsensitiveMultiMap();

    @Before
    public void before() {
        redisAPI = mock(RedisAPI.class);

        redisProvider = mock(RedisProvider.class);
        when(redisProvider.redis()).thenReturn(Future.succeededFuture(redisAPI));

        doAnswer(invocation -> {
            Handler<AsyncResult<Long>> handler = (Handler<AsyncResult<Long>>) invocation.getArguments()[1];
            handler.handle(Future.succeededFuture(555L));
            return null;
        }).when(redisAPI).incr(eq("delta:sequence"), any());

        requestHeaders = MultiMap.caseInsensitiveMultiMap();
        requestHeaders.add("x-delta", "auto");

        ruleProvider = mock(RuleProvider.class);

        request = mock(HttpServerRequest.class);
        response = mock(HttpServerResponse.class);
        when(request.response()).thenReturn(response);
        when(request.method()).thenReturn(HttpMethod.PUT);
        when(request.path()).thenReturn("/a/b/c");
        when(request.headers()).thenReturn(requestHeaders);
    }

    @Test
    public void testIsDeltaRequest(TestContext context) {
        DeltaHandler deltaHandler = new DeltaHandler(redisProvider, null, ruleProvider);
        deltaHandler.rulesChanged(List.of(
                rule("/gateleen/server/res_1", false),
                rule("/gateleen/server/res_2", true))
        );

        HttpServerRequest request = mock(HttpServerRequest.class);

        /*
         * No Rule config, No delta param, No request header => no delta request
         */
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.uri()).thenReturn("/gateleen/server/res_1");
        when(request.params()).thenReturn(new HeadersMultiMap());
        when(request.headers()).thenReturn(new HeadersMultiMap());
        context.assertFalse(deltaHandler.isDeltaRequest(request));

        /*
         * No Rule config, delta param, no request header => delta request
         */
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.uri()).thenReturn("/gateleen/server/res_1");
        when(request.params()).thenReturn(new HeadersMultiMap().add("delta", "0"));
        when(request.headers()).thenReturn(new HeadersMultiMap());
        context.assertTrue(deltaHandler.isDeltaRequest(request));

        /*
         * No Rule config, no delta param, request header => no delta request
         */
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.uri()).thenReturn("/gateleen/server/res_1");
        when(request.params()).thenReturn(new HeadersMultiMap());
        when(request.headers()).thenReturn(new HeadersMultiMap().add("x-delta-backend", "true"));
        context.assertFalse(deltaHandler.isDeltaRequest(request));

        /*
         * No Rule config, delta param, request header => no delta request
         */
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.uri()).thenReturn("/gateleen/server/res_1");
        when(request.params()).thenReturn(new HeadersMultiMap().add("delta", "0"));
        when(request.headers()).thenReturn(new HeadersMultiMap().add("x-delta-backend", "true"));
        context.assertFalse(deltaHandler.isDeltaRequest(request));

        /*
         * Rule config, No delta param, no request header => no delta request
         */
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.uri()).thenReturn("/gateleen/server/res_2");
        when(request.params()).thenReturn(new HeadersMultiMap());
        when(request.headers()).thenReturn(new HeadersMultiMap());
        context.assertFalse(deltaHandler.isDeltaRequest(request));

        /*
         * Rule config, No delta param, request header => no delta request
         */
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.uri()).thenReturn("/gateleen/server/res_2");
        when(request.params()).thenReturn(new HeadersMultiMap());
        when(request.headers()).thenReturn(new HeadersMultiMap().add("x-delta-backend", "true"));
        context.assertFalse(deltaHandler.isDeltaRequest(request));

        /*
         * Rule config, delta param, no request header => no delta request
         */
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.uri()).thenReturn("/gateleen/server/res_2");
        when(request.params()).thenReturn(new HeadersMultiMap().add("delta", "0"));
        when(request.headers()).thenReturn(new HeadersMultiMap());
        context.assertFalse(deltaHandler.isDeltaRequest(request));

        /*
         * Rule config, delta param, request header => no delta request
         */
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.uri()).thenReturn("/gateleen/server/res_2");
        when(request.params()).thenReturn(new HeadersMultiMap().add("delta", "0"));
        when(request.headers()).thenReturn(new HeadersMultiMap().add("x-delta-backend", "true"));
        context.assertFalse(deltaHandler.isDeltaRequest(request));
    }

    @Test
    public void testDeltaNoExpiry() {
        DeltaHandler deltaHandler = new DeltaHandler(redisProvider, null, ruleProvider);
        deltaHandler.handle(request, router);

        verify(redisAPI, times(1)).set(eq(Arrays.asList("delta:resources:a:b:c", "555")), any());
        verify(redisAPI, never()).setex(any(), any(), any(), any());
    }

    @Test
    public void testDeltaWithExpiry() {
        requestHeaders.add("x-expire-after", "123");

        DeltaHandler deltaHandler = new DeltaHandler(redisProvider, null, ruleProvider);
        deltaHandler.handle(request, router);

        verify(redisAPI, times(1)).setex(eq("delta:resources:a:b:c"), eq("123"), eq("555"), any());
        verify(redisAPI, never()).set(any(), any());
    }

    @Test
    public void testFailingRedisProviderAccess(TestContext context) {
        requestHeaders.add("x-expire-after", "123");

        when(redisProvider.redis()).thenReturn(Future.failedFuture("Boooom"));

        ArgumentCaptor<Integer> statusCodeCaptor = ArgumentCaptor.forClass(Integer.class);
        when(request.response().setStatusCode(statusCodeCaptor.capture())).thenReturn(response);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        when(request.response().end(bodyCaptor.capture())).thenReturn(Future.succeededFuture());

        DeltaHandler deltaHandler = new DeltaHandler(redisProvider, null, ruleProvider);
        deltaHandler.handle(request, router);

        context.assertEquals(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode(), statusCodeCaptor.getValue(), "StatusCode should be 500");
        context.assertTrue(bodyCaptor.getValue().startsWith("handleResourcePUT"));
    }

    @Test
    public void testRejectLimitOffsetParameters(TestContext context) {
        DeltaHandler deltaHandler = new DeltaHandler(redisProvider, null, ruleProvider, true);
        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        DeltaRequest request = new DeltaRequest(MultiMap.caseInsensitiveMultiMap()
                .add("delta", "0")
                .add("limit", "2"), response);

        deltaHandler.handle(request, router);
        context.assertEquals(StatusCode.BAD_REQUEST.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 400");

        request = new DeltaRequest(MultiMap.caseInsensitiveMultiMap()
                .add("delta", "0")
                .add("offset", "55"), response);

        deltaHandler.handle(request, router);
        context.assertEquals(StatusCode.BAD_REQUEST.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 400");

        request = new DeltaRequest(MultiMap.caseInsensitiveMultiMap()
                .add("delta", "0")
                .add("limit", "10")
                .add("offset", "55"), response);

        deltaHandler.handle(request, router);
        context.assertEquals(StatusCode.BAD_REQUEST.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 400");
    }

    private Rule rule(String url, boolean deltaOnBackend) {
        Rule rule = new Rule();
        rule.setUrlPattern(url);
        rule.setDeltaOnBackend(deltaOnBackend);
        return rule;
    }

    static class DeltaRequest extends DummyHttpServerRequest {

        private final MultiMap params;
        private final DummyHttpServerResponse response;

        public DeltaRequest(MultiMap params, DummyHttpServerResponse response) {
            this.params = params;
            this.response = response;
        }

        @Override
        public HttpMethod method() {
            return HttpMethod.GET;
        }

        @Override
        public String uri() {
            return "/gateleen/server/deltaResources";
        }

        @Override
        public MultiMap params() {
            return params;
        }

        @Override
        public DummyHttpServerResponse response() {
            return response;
        }

        @Override
        public MultiMap headers() {
            return MultiMap.caseInsensitiveMultiMap();
        }
    }
}

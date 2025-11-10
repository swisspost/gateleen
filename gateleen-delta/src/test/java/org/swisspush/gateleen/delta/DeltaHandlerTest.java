package org.swisspush.gateleen.delta;

import com.google.common.collect.ImmutableMap;
import io.vertx.core.*;
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
import org.swisspush.gateleen.core.event.TrackableEventPublish;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.http.DummyHttpServerResponse;
import org.swisspush.gateleen.core.redis.RedisByNameProvider;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.logging.LogAppenderRepository;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.routing.Router;
import org.swisspush.gateleen.routing.Rule;
import org.swisspush.gateleen.routing.RuleProvider;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@RunWith(VertxUnitRunner.class)
public class DeltaHandlerTest {

    private RedisAPI redisAPI;
    private RedisByNameProvider redisProvider;
    private RuleProvider ruleProvider;
    private Vertx vertx;
    private LoggingResourceManager loggingResourceManager;
    private LogAppenderRepository logAppenderRepository;
    private Router router = mock(Router.class);
    private HttpServerRequest request;
    private HttpServerResponse response;
    private MultiMap requestHeaders = MultiMap.caseInsensitiveMultiMap();

    private final String rulesPath = "/gateleen/server/admin/v1/routing/rules";

    @Before
    public void before() {
        redisAPI = mock(RedisAPI.class);
        vertx = mock(Vertx.class);
        loggingResourceManager = mock(LoggingResourceManager.class);
        logAppenderRepository = mock(LogAppenderRepository.class);

        redisProvider = mock(RedisByNameProvider.class);
        when(redisProvider.redis(any())).thenReturn(Future.succeededFuture(redisAPI));

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
    public void testStorageNameUsed(TestContext context) throws InterruptedException {
        Vertx vertx = Vertx.vertx();

        String rulesStorageInitial = "{\n" +
                " \"/gateleen/server/storage_main/(.*)\": {\n" +
                "  \"description\": \"storage main\",\n" +
                "  \"path\": \"/gateleen/server/storage_main/$1\",\n" +
                "  \"storage\": \"main\"\n" +
                " },\n" +
                " \"/gateleen/server/storage_add_0/(.*)\": {\n" +
                "  \"description\": \"add 0 storage\",\n" +
                "  \"path\": \"/gateleen/server/storage_add_0/$1\",\n" +
                "  \"storage\": \"add_0\"\n" +
                " },\n" +
                " \"/gateleen/server/storage_add_1/(.*)\": {\n" +
                "  \"description\": \"add 1 storage\",\n" +
                "  \"path\": \"/gateleen/server/storage_add_1/$1\",\n" +
                "  \"storage\": \"add_1\"\n" +
                " }\n" +
                "}";

        ResourceStorage storage = new MockResourceStorage(ImmutableMap.of(rulesPath, rulesStorageInitial));
        Map<String, Object>  properties = new HashMap<>();

        RuleProvider ruleProvider = new RuleProvider(vertx, rulesPath, storage, properties);
        Future<List<Rule>> rulesFuture = ruleProvider.getRules();
        context.assertTrue(rulesFuture.succeeded(), "getRules() future should have been successful");
        context.assertNotNull(rulesFuture.result(), "The list of rules should not be null");
        context.assertEquals(3, rulesFuture.result().size(), "There should be exactly 3 rules");

        when(request.response()).thenReturn(response);
        when(request.method()).thenReturn(HttpMethod.PUT);
        when(request.uri()).thenReturn("/gateleen/server/storage_main/res_1");
        when(request.params()).thenReturn(new HeadersMultiMap());
        when(request.headers()).thenReturn(requestHeaders);

        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);
        TrackableEventPublish.publish(vertx, Address.RULE_UPDATE_ADDRESS, true, 1000);

        Thread.sleep(2000L);

        deltaHandler.handle(request, router);
        verify(redisProvider, times(2)).redis(eq("main"));

        when(request.uri()).thenReturn("/gateleen/server/storage_add_1/res_2");
        deltaHandler.handle(request, router);
        verify(redisProvider, times(2)).redis(eq("add_1"));
    }

    @Test
    public void testIsDeltaRequest(TestContext context) {
        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);
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
        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);
        deltaHandler.handle(request, router);

        verify(redisAPI, times(1)).set(eq(Arrays.asList("delta:resources:a:b:c", "555")), any());
    }

    @Test
    public void testDeltaWithExpiryDefinedInRequestHeader() {
        requestHeaders.add("x-expire-after", "123");

        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);
        deltaHandler.handle(request, router);

        verify(redisAPI, times(1)).set(eq(Arrays.asList("delta:resources:a:b:c", "555", "EX", "123")), any());
    }

    @Test
    public void testDeltaWithExpiryDefinedInRoutingRule() throws InterruptedException {
        Vertx vertx = Vertx.vertx();

        ResourceStorage storage = new MockResourceStorage(ImmutableMap.of(rulesPath, "{\n" +
                "  \"/gateleen/rule/1\": {\n" +
                "    \"description\": \"Test rule 1\",\n" +
                "    \"path\": \"/gateleen/rule/1\",\n" +
                "    \"storage\": \"main\",\n" +
                "    \"headers\": [\n" +
                "      {\n" +
                "        \"header\": \"X-Expire-After\",\n" +
                "        \"value\": \"60\"\n" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "}"));
        Map<String, Object>  properties = new HashMap<>();

        RuleProvider ruleProvider = new RuleProvider(vertx, rulesPath, storage, properties);

        when(request.response()).thenReturn(response);
        when(request.method()).thenReturn(HttpMethod.PUT);
        when(request.uri()).thenReturn("/gateleen/rule/1");
        when(request.params()).thenReturn(new HeadersMultiMap());
        when(request.headers()).thenReturn(requestHeaders);

        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);
        TrackableEventPublish.publish(vertx, Address.RULE_UPDATE_ADDRESS, true, 1000);

        Thread.sleep(2000L);

        deltaHandler.handle(request, router);
        verify(redisProvider, times(2)).redis(eq("main"));

        // Verify that the expiry time is used (last parameter in the set command)
        verify(redisAPI, times(1)).set(eq(Arrays.asList("delta:resources:a:b:c", "555", "EX", "60")), any());
    }

    @Test
    public void testDeltaWithExpiryOverwrittenByRoutingRule() throws InterruptedException {
        Vertx vertx = Vertx.vertx();
        requestHeaders.add("x-expire-after", "123");

        ResourceStorage storage = new MockResourceStorage(ImmutableMap.of(rulesPath, "{\n" +
                "  \"/gateleen/rule/1\": {\n" +
                "    \"description\": \"Test rule 1\",\n" +
                "    \"path\": \"/gateleen/rule/1\",\n" +
                "    \"storage\": \"main\",\n" +
                "    \"headers\": [\n" +
                "      {\n" +
                "        \"header\": \"X-Expire-After\",\n" +
                "        \"value\": \"60\"\n" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "}"));
        Map<String, Object>  properties = new HashMap<>();

        RuleProvider ruleProvider = new RuleProvider(vertx, rulesPath, storage, properties);

        when(request.response()).thenReturn(response);
        when(request.method()).thenReturn(HttpMethod.PUT);
        when(request.uri()).thenReturn("/gateleen/rule/1");
        when(request.params()).thenReturn(new HeadersMultiMap());
        when(request.headers()).thenReturn(requestHeaders);

        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);
        TrackableEventPublish.publish(vertx, Address.RULE_UPDATE_ADDRESS, true, 1000);

        Thread.sleep(2000L);

        deltaHandler.handle(request, router);
        verify(redisProvider, times(2)).redis(eq("main"));

        // Verify that the expiry time is used (last parameter in the set command)
        verify(redisAPI, times(1)).set(eq(Arrays.asList("delta:resources:a:b:c", "555", "EX", "60")), any());
    }

    @Test
    public void testDeltaWithExpiryKeepHeaderValuesWithCompleteMode() throws InterruptedException {
        Vertx vertx = Vertx.vertx();
        requestHeaders.add("x-expire-after", "123");

        ResourceStorage storage = new MockResourceStorage(ImmutableMap.of(rulesPath, "{\n" +
                "  \"/gateleen/rule/1\": {\n" +
                "    \"description\": \"Test rule 1\",\n" +
                "    \"path\": \"/gateleen/rule/1\",\n" +
                "    \"storage\": \"main\",\n" +
                "    \"headers\": [\n" +
                "      {\n" +
                "        \"header\": \"X-Expire-After\",\n" +
                "        \"mode\": \"complete\",\n" +
                "        \"value\": \"60\"\n" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "}"));
        Map<String, Object>  properties = new HashMap<>();

        RuleProvider ruleProvider = new RuleProvider(vertx, rulesPath, storage, properties);

        when(request.response()).thenReturn(response);
        when(request.method()).thenReturn(HttpMethod.PUT);
        when(request.uri()).thenReturn("/gateleen/rule/1");
        when(request.params()).thenReturn(new HeadersMultiMap());
        when(request.headers()).thenReturn(requestHeaders);

        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);
        TrackableEventPublish.publish(vertx, Address.RULE_UPDATE_ADDRESS, true, 1000);
        Thread.sleep(2000L);

        deltaHandler.handle(request, router);
        verify(redisProvider, times(2)).redis(eq("main"));

        // Verify that the expiry time is used (last parameter in the set command)
        verify(redisAPI, times(1)).set(eq(Arrays.asList("delta:resources:a:b:c", "555", "EX", "123")), any());
    }

    @Test
    public void testFailingRedisProviderAccess(TestContext context) {
        requestHeaders.add("x-expire-after", "123");

        when(redisProvider.redis(any())).thenReturn(Future.failedFuture("Boooom"));

        ArgumentCaptor<Integer> statusCodeCaptor = ArgumentCaptor.forClass(Integer.class);
        when(request.response().setStatusCode(statusCodeCaptor.capture())).thenReturn(response);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        when(request.response().end(bodyCaptor.capture())).thenReturn(Future.succeededFuture());

        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);
        deltaHandler.handle(request, router);

        context.assertEquals(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode(), statusCodeCaptor.getValue(), "StatusCode should be 500");
        context.assertTrue(bodyCaptor.getValue().startsWith("handleResourcePUT"));
    }

    @Test
    public void testRejectLimitOffsetParameters(TestContext context) {
        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository, true);
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

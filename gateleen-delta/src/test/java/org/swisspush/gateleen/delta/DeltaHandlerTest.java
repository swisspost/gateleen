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
import io.vertx.redis.client.Response;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.http.DummyHttpServerResponse;
import org.swisspush.gateleen.core.redis.RedisByNameProvider;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.logging.LogAppenderRepository;
import org.swisspush.gateleen.logging.LoggingResource;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.routing.Router;
import org.swisspush.gateleen.routing.Rule;
import org.swisspush.gateleen.routing.RuleProvider;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.awaitility.Durations.TWO_SECONDS;
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
    private final Router router = mock(Router.class);
    private HttpServerRequest request;
    private HttpServerResponse response;
    private MultiMap requestHeaders = MultiMap.caseInsensitiveMultiMap();

    private final String rulesPath = "/gateleen/server/admin/v1/routing/rules";

    @Before
    public void before() {
        redisAPI = mock(RedisAPI.class);
        vertx = mock(Vertx.class);
        loggingResourceManager = mock(LoggingResourceManager.class);
        when(loggingResourceManager.getLoggingResource()).thenReturn(new LoggingResource());
        logAppenderRepository = mock(LogAppenderRepository.class);

        redisProvider = mock(RedisByNameProvider.class);
        when(redisProvider.redis(any())).thenReturn(Future.succeededFuture(redisAPI));

        doAnswer(invocation -> {
            Handler<AsyncResult<Long>> handler = (Handler<AsyncResult<Long>>) invocation.getArguments()[1];
            handler.handle(Future.succeededFuture(555L));
            return null;
        }).when(redisAPI).incr(eq("delta:sequence"), any());

        // Default: set succeeds
        doAnswer(invocation -> {
            Handler<AsyncResult<Response>> handler = (Handler<AsyncResult<Response>>) invocation.getArguments()[1];
            handler.handle(Future.succeededFuture(mock(Response.class)));
            return null;
        }).when(redisAPI).set(any(), any());

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
    public void testStorageNameUsed(TestContext context) {
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
        Map<String, Object> properties = new HashMap<>();

        RuleProvider ruleProvider = new RuleProvider(vertx, rulesPath, storage, properties);
        Future<List<Rule>> rulesFuture = ruleProvider.getRules();
        context.assertTrue(rulesFuture.succeeded(), "getRules() future should have been successful");
        context.assertNotNull(rulesFuture.result(), "The list of rules should not be null");
        context.assertEquals(3, rulesFuture.result().size(), "There should be exactly 3 rules");

        when(request.response()).thenReturn(response);
        when(request.method()).thenReturn(HttpMethod.PUT);
        when(request.uri()).thenReturn("/gateleen/server/storage_main/res_1");
        when(request.path()).thenReturn("/gateleen/server/storage_main/res_1");
        when(request.params()).thenReturn(new HeadersMultiMap());
        when(request.headers()).thenReturn(requestHeaders);

        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);
        vertx.eventBus().publish(Address.RULE_UPDATE_ADDRESS, true);

        await().atMost(TWO_SECONDS).until(() -> !deltaHandler.storageRules.isEmpty());

        deltaHandler.handle(request, router);
        verify(redisProvider, times(2)).redis(eq("main"));

        when(request.uri()).thenReturn("/gateleen/server/storage_add_1/res_2");
        when(request.path()).thenReturn("/gateleen/server/storage_add_1/res_2");
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
    public void testDeltaWithExpiryDefinedInRoutingRule() {
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
        Map<String, Object> properties = new HashMap<>();

        RuleProvider ruleProvider = new RuleProvider(vertx, rulesPath, storage, properties);

        when(request.response()).thenReturn(response);
        when(request.method()).thenReturn(HttpMethod.PUT);
        when(request.uri()).thenReturn("/gateleen/rule/1");
        when(request.path()).thenReturn("/gateleen/rule/1");
        when(request.params()).thenReturn(new HeadersMultiMap());
        when(request.headers()).thenReturn(requestHeaders);

        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);
        vertx.eventBus().publish(Address.RULE_UPDATE_ADDRESS, true);

        await().atMost(TWO_SECONDS).until(() -> !deltaHandler.storageRules.isEmpty());

        deltaHandler.handle(request, router);
        verify(redisProvider, times(2)).redis(eq("main"));

        // Verify that the expiry time from the routing rule is used
        verify(redisAPI, times(1)).set(eq(Arrays.asList("delta:resources:gateleen:rule:1", "555", "EX", "60")), any());
    }

    @Test
    public void testDeltaWithExpiryOverwrittenByRoutingRule() {
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
        Map<String, Object> properties = new HashMap<>();

        RuleProvider ruleProvider = new RuleProvider(vertx, rulesPath, storage, properties);

        when(request.response()).thenReturn(response);
        when(request.method()).thenReturn(HttpMethod.PUT);
        when(request.uri()).thenReturn("/gateleen/rule/1");
        when(request.path()).thenReturn("/gateleen/rule/1");
        when(request.params()).thenReturn(new HeadersMultiMap());
        when(request.headers()).thenReturn(requestHeaders);

        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);
        vertx.eventBus().publish(Address.RULE_UPDATE_ADDRESS, true);

        await().atMost(TWO_SECONDS).until(() -> !deltaHandler.storageRules.isEmpty());

        deltaHandler.handle(request, router);
        verify(redisProvider, times(2)).redis(eq("main"));

        // Verify that the routing rule value (60) wins over the request header (123)
        verify(redisAPI, times(1)).set(eq(Arrays.asList("delta:resources:gateleen:rule:1", "555", "EX", "60")), any());
    }

    @Test
    public void testDeltaWithExpiryKeepHeaderValuesWithCompleteMode() {
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
        Map<String, Object> properties = new HashMap<>();

        RuleProvider ruleProvider = new RuleProvider(vertx, rulesPath, storage, properties);

        when(request.response()).thenReturn(response);
        when(request.method()).thenReturn(HttpMethod.PUT);
        when(request.uri()).thenReturn("/gateleen/rule/1");
        when(request.path()).thenReturn("/gateleen/rule/1");
        when(request.params()).thenReturn(new HeadersMultiMap());
        when(request.headers()).thenReturn(requestHeaders);

        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);
        vertx.eventBus().publish(Address.RULE_UPDATE_ADDRESS, true);

        await().atMost(TWO_SECONDS).until(() -> !deltaHandler.storageRules.isEmpty());

        deltaHandler.handle(request, router);
        verify(redisProvider, times(2)).redis(eq("main"));

        // In "complete" mode the existing request header value (123) is kept
        verify(redisAPI, times(1)).set(eq(Arrays.asList("delta:resources:gateleen:rule:1", "555", "EX", "123")), any());
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

    @Test
    public void testIsDeltaPUTRequestCaseInsensitive(TestContext context) {
        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);

        HttpServerRequest req = mock(HttpServerRequest.class);

        // "AUTO" uppercase should match
        when(req.method()).thenReturn(HttpMethod.PUT);
        when(req.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap().add("x-delta", "AUTO"));
        context.assertTrue(deltaHandler.isDeltaRequest(req));

        // "Auto" mixed case should match
        when(req.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap().add("x-delta", "Auto"));
        context.assertTrue(deltaHandler.isDeltaRequest(req));
    }

    @Test
    public void testIsDeltaPUTRequestNonAutoValue(TestContext context) {
        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);

        HttpServerRequest req = mock(HttpServerRequest.class);
        when(req.method()).thenReturn(HttpMethod.PUT);
        when(req.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap().add("x-delta", "manual"));
        context.assertFalse(deltaHandler.isDeltaRequest(req));
    }

    @Test
    public void testIsDeltaPUTRequestNoDeltaHeader(TestContext context) {
        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);

        HttpServerRequest req = mock(HttpServerRequest.class);
        when(req.method()).thenReturn(HttpMethod.PUT);
        when(req.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        context.assertFalse(deltaHandler.isDeltaRequest(req));
    }

    @Test
    public void testIsDeltaRequestNonGetNonPutMethod(TestContext context) {
        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);

        HttpServerRequest req = mock(HttpServerRequest.class);
        // POST with delta param should not be a delta request
        when(req.method()).thenReturn(HttpMethod.POST);
        when(req.uri()).thenReturn("/gateleen/server/res_1");
        when(req.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap().add("x-delta", "Auto"));
        context.assertFalse(deltaHandler.isDeltaRequest(req));
    }

    @Test
    public void testDeltaBackendHeaderIsRemovedWhenPresent(TestContext context) {
        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);

        MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                .add("x-delta-backend", "true");

        HttpServerRequest req = mock(HttpServerRequest.class);
        when(req.method()).thenReturn(HttpMethod.GET);
        when(req.uri()).thenReturn("/gateleen/server/res_1");
        when(req.params()).thenReturn(new HeadersMultiMap().add("delta", "0"));
        when(req.headers()).thenReturn(headers);

        // Returns false because x-delta-backend is present
        context.assertFalse(deltaHandler.isDeltaRequest(req));

        // Side-effect: the x-delta-backend header must have been removed
        context.assertFalse(headers.contains("x-delta-backend"),
                "x-delta-backend header should have been removed as a side-effect of isDeltaGETRequest");
    }

    @Test
    public void testRouterIsCalledAfterSuccessfulPut() {
        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);
        deltaHandler.handle(request, router);

        verify(router, times(1)).route(request);
    }

    @Test
    public void testRedisIncrFailureReturns500(TestContext context) {
        doAnswer(invocation -> {
            Handler<AsyncResult<Long>> handler = (Handler<AsyncResult<Long>>) invocation.getArguments()[1];
            handler.handle(Future.failedFuture("incr failed"));
            return null;
        }).when(redisAPI).incr(eq("delta:sequence"), any());

        ArgumentCaptor<Integer> statusCodeCaptor = ArgumentCaptor.forClass(Integer.class);
        when(response.setStatusCode(statusCodeCaptor.capture())).thenReturn(response);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        when(response.end(bodyCaptor.capture())).thenReturn(Future.succeededFuture());

        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);
        deltaHandler.handle(request, router);

        context.assertEquals(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode(), statusCodeCaptor.getValue());
        context.assertEquals("error incrementing/accessing sequence for update-id", bodyCaptor.getValue());
        verify(router, never()).route(any());
    }

    @Test
    public void testRedisSetFailureReturns500(TestContext context) {
        doAnswer(invocation -> {
            Handler<AsyncResult<Response>> handler = (Handler<AsyncResult<Response>>) invocation.getArguments()[1];
            handler.handle(Future.failedFuture("set failed"));
            return null;
        }).when(redisAPI).set(any(), any());

        ArgumentCaptor<Integer> statusCodeCaptor = ArgumentCaptor.forClass(Integer.class);
        when(response.setStatusCode(statusCodeCaptor.capture())).thenReturn(response);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        when(response.end(bodyCaptor.capture())).thenReturn(Future.succeededFuture());

        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);
        deltaHandler.handle(request, router);

        context.assertEquals(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode(), statusCodeCaptor.getValue());
        context.assertEquals("error saving delta information", bodyCaptor.getValue());
        verify(router, never()).route(any());
    }

    @Test
    public void testEtagNotInStoragePerformsDeltaUpdate() {
        // When no etag is stored (get returns null), delta update should proceed
        doAnswer(invocation -> {
            Handler<AsyncResult<Response>> handler = (Handler<AsyncResult<Response>>) invocation.getArguments()[1];
            handler.handle(Future.succeededFuture(null)); // null = not found in Redis
            return null;
        }).when(redisAPI).get(any(), any());

        requestHeaders.add("if-none-match", "etag-abc");

        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);
        deltaHandler.handle(request, router);

        // incr should be called (delta update performed)
        verify(redisAPI, times(1)).incr(eq("delta:sequence"), any());
        // router should be called after successful update
        verify(router, times(1)).route(request);
    }

    @Test
    public void testEtagMatchSkipsDeltaUpdate() {
        // When stored etag matches request etag, skip the delta update
        String etag = "etag-abc";

        Response etagResponse = mock(Response.class);
        when(etagResponse.toString()).thenReturn(etag);

        doAnswer(invocation -> {
            Handler<AsyncResult<Response>> handler = (Handler<AsyncResult<Response>>) invocation.getArguments()[1];
            handler.handle(Future.succeededFuture(etagResponse));
            return null;
        }).when(redisAPI).get(any(), any());

        requestHeaders.add("if-none-match", etag);

        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);
        deltaHandler.handle(request, router);

        // incr should NOT be called (skipped)
        verify(redisAPI, never()).incr(any(), any());
        // router should still be called to pass the request through
        verify(router, times(1)).route(request);
    }

    @Test
    public void testEtagMismatchPerformsDeltaUpdate() {
        // When stored etag differs from request etag, update delta
        Response storedEtagResponse = mock(Response.class);
        when(storedEtagResponse.toString()).thenReturn("old-etag");

        doAnswer(invocation -> {
            Handler<AsyncResult<Response>> handler = (Handler<AsyncResult<Response>>) invocation.getArguments()[1];
            handler.handle(Future.succeededFuture(storedEtagResponse));
            return null;
        }).when(redisAPI).get(any(), any());

        requestHeaders.add("if-none-match", "new-etag");

        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);
        deltaHandler.handle(request, router);

        // incr should be called (delta update performed)
        verify(redisAPI, times(1)).incr(eq("delta:sequence"), any());
        verify(router, times(1)).route(request);
    }

    @Test
    public void testEtagRedisGetFailureProceedsWithDeltaUpdate() {
        // When Redis GET fails, fall back to performing the delta update
        doAnswer(invocation -> {
            Handler<AsyncResult<Response>> handler = (Handler<AsyncResult<Response>>) invocation.getArguments()[1];
            handler.handle(Future.failedFuture("get failed"));
            return null;
        }).when(redisAPI).get(any(), any());

        requestHeaders.add("if-none-match", "etag-xyz");

        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);
        deltaHandler.handle(request, router);

        // incr should still be called (proceed with update despite GET failure)
        verify(redisAPI, times(1)).incr(eq("delta:sequence"), any());
    }

    @Test
    public void testEtagRedisProviderFailureProceedsWithDeltaUpdate() {
        // When redisProvider.redis() fails in the etag path, fall back to performing the delta update
        // First call (for get) fails; second call (for incr) succeeds
        when(redisProvider.redis(any()))
                .thenReturn(Future.failedFuture("provider failed"))   // etag GET
                .thenReturn(Future.succeededFuture(redisAPI));         // incr + set

        requestHeaders.add("if-none-match", "etag-xyz");

        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);
        deltaHandler.handle(request, router);

        // incr should be called (proceeded with update despite provider failure)
        verify(redisAPI, times(1)).incr(eq("delta:sequence"), any());
    }

    @Test
    public void testNegativeExpireAfterHeaderResultsInNoExpiry() {
        requestHeaders.add("x-expire-after", "-5");

        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);
        deltaHandler.handle(request, router);

        // No EX option should be added when value is negative
        verify(redisAPI, times(1)).set(eq(Arrays.asList("delta:resources:a:b:c", "555")), any());
    }

    @Test
    public void testNonNumericExpireAfterHeaderResultsInNoExpiry() {
        requestHeaders.add("x-expire-after", "not-a-number");

        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);
        deltaHandler.handle(request, router);

        // No EX option should be added when value is non-numeric
        verify(redisAPI, times(1)).set(eq(Arrays.asList("delta:resources:a:b:c", "555")), any());
    }

    @Test
    public void testZeroExpireAfterHeaderIsPassedThrough() {
        requestHeaders.add("x-expire-after", "0");

        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);
        deltaHandler.handle(request, router);

        // Zero is a valid non-negative value, so EX 0 is sent (Redis may reject it, but that's Redis's concern)
        verify(redisAPI, times(1)).set(eq(Arrays.asList("delta:resources:a:b:c", "555", "EX", "0")), any());
    }

    @Test
    public void testRulesWithNullStorageAreIgnored() {
        DeltaHandler deltaHandler = new DeltaHandler(vertx, redisProvider, null, ruleProvider, loggingResourceManager, logAppenderRepository);

        Rule ruleWithoutStorage = new Rule();
        ruleWithoutStorage.setUrlPattern("/gateleen/server/no-storage");
        // storage is null by default

        deltaHandler.rulesChanged(List.of(ruleWithoutStorage));

        // storageRules should still be empty since this rule has no storage
        verify(redisProvider, never()).redis(any());
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

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

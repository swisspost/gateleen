package org.swisspush.gateleen.expansion;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.routing.Rule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.*;
import static org.swisspush.gateleen.expansion.ExpansionHandler.EXPAND_PARAM;
import static org.swisspush.gateleen.expansion.ExpansionHandler.ZIP_PARAM;

/**
 * Tests for the {@link ExpansionHandler} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class ExpansionHandlerTest {

    private static final String ROOT = "/gateleen";
    private static final String RULES_ROOT = ROOT + "/server/admin/v1/routing/rules";

    private Vertx vertx;
    private HttpClient httpClient;
    private MockResourceStorage storage;
    private ExpansionHandler expansionHandler;

    @Before
    public void setUp() {
        vertx = Vertx.vertx();
        httpClient = Mockito.mock(HttpClient.class);
        storage = new MockResourceStorage();
    }

    @Test
    public void testExpansionConfigurationDefaultValues(TestContext context) {
        Map<String, Object> properties = new HashMap<>();
        expansionHandler = new ExpansionHandler(vertx, storage, httpClient, properties, ROOT, RULES_ROOT);

        context.assertEquals(Integer.MAX_VALUE, expansionHandler.getMaxExpansionLevelSoft(), "max.expansion.level.soft should have the default value");
        context.assertEquals(Integer.MAX_VALUE, expansionHandler.getMaxExpansionLevelHard(), "max.expansion.level.soft should have the default value");
        context.assertEquals(20000, expansionHandler.getMaxSubRequestCount(), "max.expansion.subrequests should have the default value");
    }

    @Test
    public void testExpansionConfigurationCustomValues(TestContext context) {
        Map<String, Object> properties = new HashMap<>();

        properties.put("max.expansion.subrequests", "500");
        properties.put("max.expansion.level.soft", "1000");
        properties.put("max.expansion.level.hard", "1500");

        expansionHandler = new ExpansionHandler(vertx, storage, httpClient, properties, ROOT, RULES_ROOT);

        context.assertEquals(1000, expansionHandler.getMaxExpansionLevelSoft(), "max.expansion.level.soft should have the default value");
        context.assertEquals(1500, expansionHandler.getMaxExpansionLevelHard(), "max.expansion.level.soft should have the default value");
        context.assertEquals(500, expansionHandler.getMaxSubRequestCount(), "max.expansion.subrequests should have the default value");
    }

    @Test
    public void testExpansionConfigurationInvalidValues(TestContext context) {
        Map<String, Object> properties = new HashMap<>();

        properties.put("max.expansion.subrequests", "abc");
        properties.put("max.expansion.level.soft", "xyz");
        properties.put("max.expansion.level.hard", "123x");

        expansionHandler = new ExpansionHandler(vertx, storage, httpClient, properties, ROOT, RULES_ROOT);

        context.assertEquals(Integer.MAX_VALUE, expansionHandler.getMaxExpansionLevelSoft(), "max.expansion.level.soft should have the default value");
        context.assertEquals(Integer.MAX_VALUE, expansionHandler.getMaxExpansionLevelHard(), "max.expansion.level.soft should have the default value");
        context.assertEquals(20000, expansionHandler.getMaxSubRequestCount(), "max.expansion.subrequests should have the default value");
    }

    @Test
    public void testIsExpansionRequest(TestContext context) {
        expansionHandler = new ExpansionHandler(vertx, storage, httpClient, new HashMap<>(), ROOT, RULES_ROOT);

        MultiMap params = MultiMap.caseInsensitiveMultiMap();
        params.set(EXPAND_PARAM, "4");

        context.assertFalse(expansionHandler.isExpansionRequest(new Request(HttpMethod.PUT, "/some/uri", params)),
                "PUT requests should not be expansion requests");
        context.assertTrue(expansionHandler.isExpansionRequest(new Request(HttpMethod.GET, "/some/uri", params)),
                "GET request with correct params and not configured as expandOnBackend should be expansion requests");
        context.assertFalse(expansionHandler.isExpansionRequest(new Request(HttpMethod.GET, "/some/uri", MultiMap.caseInsensitiveMultiMap())),
                "GET requests without correct params should not be expansion requests");

        List<Rule> rules = new ArrayList<>();
        Rule rule1 = new Rule();
        rule1.setUrlPattern("/some/backendExpand/uri");
        rule1.setExpandOnBackend(true);
        rules.add(rule1);
        expansionHandler.rulesChanged(rules);

        context.assertFalse(expansionHandler.isExpansionRequest(new Request(HttpMethod.GET, "/some/backendExpand/uri", params)),
                "GET request with correct params and configured as expandOnBackend should not be expansion requests");
    }

    @Test
    public void testIsZipRequest(TestContext context) {
        expansionHandler = new ExpansionHandler(vertx, storage, httpClient, new HashMap<>(), ROOT, RULES_ROOT);

        MultiMap params = MultiMap.caseInsensitiveMultiMap();
        params.set(ZIP_PARAM, "true");

        context.assertFalse(expansionHandler.isZipRequest(new Request(HttpMethod.PUT, "/some/uri", params)),
                "PUT requests should not be zip requests");
        context.assertTrue(expansionHandler.isZipRequest(new Request(HttpMethod.GET, "/some/uri", params)),
                "GET request with correct params and not configured as expandOnBackend should be zip requests");

        params.set(ZIP_PARAM, "false");
        context.assertFalse(expansionHandler.isZipRequest(new Request(HttpMethod.GET, "/some/uri", params)),
                "GET request with correct params (value=false) and not configured as expandOnBackend should not be zip requests");

        params.set(ZIP_PARAM, "foobar");
        context.assertTrue(expansionHandler.isZipRequest(new Request(HttpMethod.GET, "/some/uri", params)),
                "GET request with correct params (value != false) and not configured as expandOnBackend should be zip requests");

        context.assertFalse(expansionHandler.isZipRequest(new Request(HttpMethod.GET, "/some/uri", MultiMap.caseInsensitiveMultiMap())),
                "GET requests without correct params should not be zip requests");

        List<Rule> rules = new ArrayList<>();
        Rule rule1 = new Rule();
        rule1.setUrlPattern("/some/backendExpand/uri");
        rule1.setExpandOnBackend(true);
        rules.add(rule1);
        expansionHandler.rulesChanged(rules);

        params.set(ZIP_PARAM, "true");
        context.assertFalse(expansionHandler.isZipRequest(new Request(HttpMethod.GET, "/some/backendExpand/uri", params)),
                "GET request with correct params and configured as expandOnBackend should not be zip requests");
    }

    @Test
    public void testIsBackendExpand(TestContext context) {
        expansionHandler = new ExpansionHandler(vertx, storage, httpClient, new HashMap<>(), ROOT, RULES_ROOT);

        context.assertFalse(expansionHandler.isBackendExpand("/some/request/uri"),
                "uri should not be a backend expand since no routing rules have been defined yet");

        List<Rule> rules = new ArrayList<>();
        Rule rule1 = new Rule();
        rule1.setUrlPattern("/test/rules/rule/backendExpand");
        rule1.setExpandOnBackend(true);
        rules.add(rule1);

        Rule rule2 = new Rule();
        rule2.setUrlPattern("/test/rules/rule/notBackendExpand");
        rule2.setExpandOnBackend(false);
        rules.add(rule2);

        expansionHandler.rulesChanged(rules);

        context.assertTrue(expansionHandler.isBackendExpand("/test/rules/rule/backendExpand"),
                "uri should be a backend expand");
        context.assertFalse(expansionHandler.isBackendExpand("/test/rules/rule/notBackendExpand"),
                "uri should not be a backend expand");
        context.assertFalse(expansionHandler.isBackendExpand("/some/other/request/uri"),
                "uri should not be a backend expand");
    }

    @Test
    public void testIsStorageExpand(TestContext context) {
        expansionHandler = new ExpansionHandler(vertx, storage, httpClient, new HashMap<>(), ROOT, RULES_ROOT);

        context.assertFalse(expansionHandler.isStorageExpand("/some/request/uri"),
                "uri should not be a storage expand since no routing rules have been defined yet");

        List<Rule> rules = new ArrayList<>();
        Rule rule1 = new Rule();
        rule1.setUrlPattern("/test/rules/rule/storageExpand");
        rule1.setStorageExpand(true);
        rules.add(rule1);

        Rule rule2 = new Rule();
        rule2.setUrlPattern("/test/rules/rule/notStorageExpand");
        rule2.setStorageExpand(false);
        rules.add(rule2);

        expansionHandler.rulesChanged(rules);

        context.assertTrue(expansionHandler.isStorageExpand("/test/rules/rule/storageExpand"),
                "uri should be a storage expand");
        context.assertFalse(expansionHandler.isStorageExpand("/test/rules/rule/notStorageExpand"),
                "uri should not be a storage expand");
        context.assertFalse(expansionHandler.isStorageExpand("/some/other/request/uri"),
                "uri should not be a storage expand");
    }

    @Test
    public void testIsStorageExpandRequestParameter(TestContext context) {
        expansionHandler = new ExpansionHandler(vertx, storage, httpClient, new HashMap<>(), ROOT, RULES_ROOT);

        MultiMap params = MultiMap.caseInsensitiveMultiMap();
        params.set(ExpansionHandler.STORAGE_EXPAND_URL_PARAM, "true");
        context.assertTrue(
                expansionHandler.isStorageExpand(new Request(HttpMethod.GET, "/some/collection/", params)),
                "storageExpand=true should enable storage expansion");

        params.set(ExpansionHandler.STORAGE_EXPAND_URL_PARAM, "false");
        context.assertFalse(
                expansionHandler.isStorageExpand(new Request(HttpMethod.GET, "/some/collection/", params)),
                "storageExpand=false should not enable storage expansion");

        params.set(ExpansionHandler.STORAGE_EXPAND_URL_PARAM, "true");
        params.set(EXPAND_PARAM, "2");
        HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
        expansionHandler.handleExpansionRecursion(
                new Request(HttpMethod.GET, "/some/collection/", params, response));
        verify(response).end("Expand values higher than 1 are not supported for storageExpand requests");
        verifyNoInteractions(httpClient);
    }

    @Test
    public void testChildRequestFailureEndsExpansionWithInternalServerError(TestContext context) throws InterruptedException {
        expansionHandler = new ExpansionHandler(vertx, storage, httpClient, new HashMap<>(), ROOT, RULES_ROOT);

        Buffer collection = Buffer.buffer("{\"collection\":[\"a\",\"b\"]}");
        HttpClientResponse cRes = Mockito.mock(HttpClientResponse.class);
        when(cRes.statusCode()).thenReturn(200);
        when(cRes.statusMessage()).thenReturn("OK");
        when(cRes.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        doAnswer(inv -> {
            ((Handler<Buffer>) inv.getArgument(0)).handle(collection);
            return cRes;
        }).when(cRes).bodyHandler(any());

        HttpClientRequest cReq = Mockito.mock(HttpClientRequest.class);
        when(cReq.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        doAnswer(inv -> {
            ((Handler<AsyncResult<HttpClientResponse>>) inv.getArgument(0)).handle(Future.succeededFuture(cRes));
            return null;
        }).when(cReq).send(any(Handler.class));

        when(httpClient.request(eq(HttpMethod.GET), eq("/some/collection/"))).thenReturn(Future.succeededFuture(cReq));
        when(httpClient.request(eq(HttpMethod.GET), eq("/some/collection/a")))
                .thenThrow(new IllegalStateException("child request failed"));

        HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
        when(response.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        MultiMap params = MultiMap.caseInsensitiveMultiMap();
        params.set(EXPAND_PARAM, "1");

        expansionHandler.handleExpansionRecursion(new Request(HttpMethod.GET, "/some/collection/", params, response));

        verify(response, timeout(5000)).setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
        verify(response, timeout(5000)).end("child request failed");
        // leave time for already queued slices to run
        Thread.sleep(300);
        verify(httpClient, never()).request(HttpMethod.GET, "/some/collection/b");
    }

    @Test
    public void testAsynchronousChildRequestFailureEndsExpansionWithInternalServerError(TestContext context) throws InterruptedException {
        try {
            expansionHandler = new ExpansionHandler(vertx, storage, httpClient, new HashMap<>(), ROOT, RULES_ROOT);

            Buffer collection = Buffer.buffer("{\"collection\":[\"a\",\"b\"]}");
            CountDownLatch childRequestIssued = new CountDownLatch(1);
            CountDownLatch failureDelivered = new CountDownLatch(1);
            Promise<HttpClientRequest> failedChildRequest = Promise.promise();
            failedChildRequest.future().onFailure(ignored -> failureDelivered.countDown());
            String failureMessage = "asynchronous child request failed";

            when(httpClient.request(eq(HttpMethod.GET), anyString())).thenAnswer(invocation -> {
                String uri = invocation.getArgument(1);
                if ("/some/collection/a".equals(uri)) {
                    childRequestIssued.countDown();
                    vertx.runOnContext(ignored -> failedChildRequest.fail(failureMessage));
                    return failedChildRequest.future();
                }
                Buffer body = "/some/collection/".equals(uri) ? collection : Buffer.buffer("{}");
                return Future.succeededFuture(successfulRequest(body));
            });

            MultiMap params = MultiMap.caseInsensitiveMultiMap();
            params.set(EXPAND_PARAM, "1");
            HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
            when(response.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());

            expansionHandler.handleExpansionRecursion(
                    new Request(HttpMethod.GET, "/some/collection/", params, response));

            context.assertTrue(childRequestIssued.await(2, TimeUnit.SECONDS),
                    "the failing child request should be issued");
            context.assertTrue(failureDelivered.await(2, TimeUnit.SECONDS),
                    "the child request failure should be delivered asynchronously");
            verify(response, timeout(2000))
                    .setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
            verify(response, timeout(2000)).end(failureMessage);
        } finally {
            vertx.close();
        }
    }

    @Test
    public void testStorageExpandRuleMatchesNestedCollection(TestContext context) {
        try {
            expansionHandler = new ExpansionHandler(vertx, storage, httpClient, new HashMap<>(), ROOT, RULES_ROOT);

            List<Rule> rules = new ArrayList<>();
            Rule nestedStorageExpandRule = new Rule();
            nestedStorageExpandRule.setUrlPattern("/some/collection/group");
            nestedStorageExpandRule.setStorageExpand(true);
            rules.add(nestedStorageExpandRule);
            expansionHandler.rulesChanged(rules);

            when(httpClient.request(eq(HttpMethod.GET), anyString())).thenAnswer(invocation -> {
                String uri = invocation.getArgument(1);
                Buffer body;
                if ("/some/collection/".equals(uri)) {
                    body = Buffer.buffer("{\"collection\":[\"group/\"]}");
                } else if ("/some/collection/group".equals(uri)) {
                    body = Buffer.buffer("{\"group\":[\"item\"]}");
                } else {
                    body = Buffer.buffer("{}");
                }
                return Future.succeededFuture(successfulRequest(body));
            });
            HttpClientRequest storageExpandRequest = successfulRequest(Buffer.buffer("{}"));
            when(httpClient.request(eq(HttpMethod.POST), eq("/some/collection/group?storageExpand=true")))
                    .thenReturn(Future.succeededFuture(storageExpandRequest));

            MultiMap params = MultiMap.caseInsensitiveMultiMap();
            params.set(EXPAND_PARAM, "2");
            HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
            when(response.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());

            expansionHandler.handleExpansionRecursion(
                    new Request(HttpMethod.GET, "/some/collection/?expand=2", params, response));

            verify(httpClient, timeout(2000)).request(HttpMethod.GET, "/some/collection/group");
            verify(httpClient, timeout(2000)).request(
                    HttpMethod.POST, "/some/collection/group?storageExpand=true");
            verify(httpClient, never()).request(HttpMethod.GET, "/some/collection/group/item");
        } finally {
            vertx.close();
        }
    }

    @Test
    public void testAsynchronousChildRequestFailureStopsIssuingFurtherChildRequests(TestContext context) throws InterruptedException {
        try {
            expansionHandler = new ExpansionHandler(vertx, storage, httpClient, new HashMap<>(), ROOT, RULES_ROOT);

            Buffer collection = Buffer.buffer("{\"collection\":[\"a\",\"b\",\"c\"]}");
            String failureMessage = "child request a failed";

            when(httpClient.request(eq(HttpMethod.GET), anyString())).thenAnswer(invocation -> {
                String uri = invocation.getArgument(1);
                if ("/some/collection/a".equals(uri)) {
                    return Future.failedFuture(failureMessage);
                }
                Buffer body = "/some/collection/".equals(uri) ? collection : Buffer.buffer("{}");
                return Future.succeededFuture(successfulRequest(body));
            });

            MultiMap params = MultiMap.caseInsensitiveMultiMap();
            params.set(EXPAND_PARAM, "1");
            HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
            when(response.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());

            expansionHandler.handleExpansionRecursion(
                    new Request(HttpMethod.GET, "/some/collection/", params, response));

            verify(response, timeout(2000))
                    .setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
            verify(response, timeout(2000)).end(failureMessage);
            verify(httpClient, never()).request(HttpMethod.GET, "/some/collection/b");
            verify(httpClient, never()).request(HttpMethod.GET, "/some/collection/c");
        } finally {
            vertx.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testChildFailureCompletesResponseWithoutWaitingForInFlightSiblings(TestContext context) throws InterruptedException {
        try {
            expansionHandler = new ExpansionHandler(vertx, storage, httpClient, new HashMap<>(), ROOT, RULES_ROOT);

            Buffer collection = Buffer.buffer("{\"collection\":[\"a\",\"b\"]}");
            String failureMessage = "child request b failed";
            AtomicReference<Handler<AsyncResult<HttpClientResponse>>> pendingSendOfA = new AtomicReference<>();

            HttpClientRequest slowRequestA = Mockito.mock(HttpClientRequest.class);
            when(slowRequestA.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
            doAnswer(inv -> {
                pendingSendOfA.set(inv.getArgument(0));
                return null;
            }).when(slowRequestA).send(any(Handler.class));

            when(httpClient.request(eq(HttpMethod.GET), anyString())).thenAnswer(invocation -> {
                String uri = invocation.getArgument(1);
                if ("/some/collection/a".equals(uri)) {
                    return Future.succeededFuture(slowRequestA);
                }
                if ("/some/collection/b".equals(uri)) {
                    return Future.failedFuture(failureMessage);
                }
                return Future.succeededFuture(successfulRequest(collection));
            });

            MultiMap params = MultiMap.caseInsensitiveMultiMap();
            params.set(EXPAND_PARAM, "1");
            HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
            when(response.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());

            expansionHandler.handleExpansionRecursion(
                    new Request(HttpMethod.GET, "/some/collection/", params, response));

            // child a is still in flight, the response must already be completed by the failure of b
            verify(response, timeout(2000)).end(failureMessage);

            // a late result of the in-flight sibling must not complete the response a second time
            HttpClientResponse lateResponse = Mockito.mock(HttpClientResponse.class);
            when(lateResponse.statusCode()).thenReturn(200);
            when(lateResponse.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
            doAnswer(inv -> {
                ((Handler<Buffer>) inv.getArgument(0)).handle(Buffer.buffer("{}"));
                return lateResponse;
            }).when(lateResponse).bodyHandler(any());
            context.assertNotNull(pendingSendOfA.get(), "child a should have been sent");
            pendingSendOfA.get().handle(Future.succeededFuture(lateResponse));

            Thread.sleep(300);
            verify(response, times(1)).end(anyString());
            verify(response, never()).end();
        } finally {
            vertx.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testStorageExpandSendFailureEndsExpansionWithInternalServerError(TestContext context) throws InterruptedException {
        try {
            expansionHandler = new ExpansionHandler(vertx, storage, httpClient, new HashMap<>(), ROOT, RULES_ROOT);

            Rule storageExpandRule = new Rule();
            storageExpandRule.setUrlPattern("/some/collection/");
            storageExpandRule.setStorageExpand(true);
            expansionHandler.rulesChanged(new ArrayList<>(List.of(storageExpandRule)));

            Buffer collection = Buffer.buffer("{\"collection\":[\"a\",\"b\"]}");
            String failureMessage = "storage expand send failed";

            HttpClientRequest failingSend = Mockito.mock(HttpClientRequest.class);
            when(failingSend.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
            doAnswer(inv -> {
                ((Handler<AsyncResult<HttpClientResponse>>) inv.getArgument(0))
                        .handle(Future.failedFuture(failureMessage));
                return null;
            }).when(failingSend).send(any(Handler.class));

            HttpClientRequest rootRequest = successfulRequest(collection);
            when(httpClient.request(eq(HttpMethod.GET), anyString()))
                    .thenReturn(Future.succeededFuture(rootRequest));
            when(httpClient.request(eq(HttpMethod.POST), eq("/some/collection/?storageExpand=true")))
                    .thenReturn(Future.succeededFuture(failingSend));

            MultiMap params = MultiMap.caseInsensitiveMultiMap();
            params.set(EXPAND_PARAM, "1");
            HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
            when(response.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());

            expansionHandler.handleExpansionRecursion(
                    new Request(HttpMethod.GET, "/some/collection/", params, response));

            verify(response, timeout(2000))
                    .setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
            verify(response, timeout(2000)).end(failureMessage);
            Thread.sleep(300);
            verify(response, times(1)).end(anyString());
        } finally {
            vertx.close();
        }
    }

    @Test
    public void testNestedCollectionChildFailureStopsOtherBranches(TestContext context) throws InterruptedException {
        try {
            expansionHandler = new ExpansionHandler(vertx, storage, httpClient, new HashMap<>(), ROOT, RULES_ROOT);

            String failureMessage = "nested child request failed";
            when(httpClient.request(eq(HttpMethod.GET), anyString())).thenAnswer(invocation -> {
                String uri = invocation.getArgument(1);
                switch (uri) {
                    case "/some/collection/":
                        return Future.succeededFuture(successfulRequest(
                                Buffer.buffer("{\"collection\":[\"group/\",\"other/\"]}")));
                    case "/some/collection/group":
                        return Future.succeededFuture(successfulRequest(
                                Buffer.buffer("{\"group\":[\"a\",\"b\"]}")));
                    case "/some/collection/group/a":
                        return Future.failedFuture(failureMessage);
                    default:
                        return Future.succeededFuture(successfulRequest(Buffer.buffer("{}")));
                }
            });

            MultiMap params = MultiMap.caseInsensitiveMultiMap();
            params.set(EXPAND_PARAM, "2");
            HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
            when(response.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());

            expansionHandler.handleExpansionRecursion(
                    new Request(HttpMethod.GET, "/some/collection/", params, response));

            verify(response, timeout(2000))
                    .setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
            verify(response, timeout(2000)).end(failureMessage);
            Thread.sleep(300);
            verify(httpClient, never()).request(HttpMethod.GET, "/some/collection/group/b");
            verify(response, times(1)).end(anyString());
        } finally {
            vertx.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testLateChildRequestFailureAfterSiblingsIssuedEndsExpansion(TestContext context) throws InterruptedException {
        try {
            expansionHandler = new ExpansionHandler(vertx, storage, httpClient, new HashMap<>(), ROOT, RULES_ROOT);

            Buffer collection = Buffer.buffer("{\"collection\":[\"a\",\"b\",\"c\"]}");
            String failureMessage = "late child request failure";
            CountDownLatch allChildrenIssued = new CountDownLatch(3);
            Promise<HttpClientRequest> lateFailure = Promise.promise();

            HttpClientRequest inFlight = Mockito.mock(HttpClientRequest.class);
            when(inFlight.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());

            when(httpClient.request(eq(HttpMethod.GET), anyString())).thenAnswer(invocation -> {
                String uri = invocation.getArgument(1);
                if ("/some/collection/".equals(uri)) {
                    return Future.succeededFuture(successfulRequest(collection));
                }
                allChildrenIssued.countDown();
                if ("/some/collection/b".equals(uri)) {
                    return lateFailure.future();
                }
                return Future.succeededFuture(inFlight);
            });

            MultiMap params = MultiMap.caseInsensitiveMultiMap();
            params.set(EXPAND_PARAM, "1");
            HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
            when(response.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());

            expansionHandler.handleExpansionRecursion(
                    new Request(HttpMethod.GET, "/some/collection/", params, response));

            context.assertTrue(allChildrenIssued.await(2, TimeUnit.SECONDS),
                    "all children should be issued before the failure is delivered");
            vertx.runOnContext(ignored -> lateFailure.fail(failureMessage));

            verify(response, timeout(2000))
                    .setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
            verify(response, timeout(2000)).end(failureMessage);
        } finally {
            vertx.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testChildResponseBodyFailureEndsExpansionWithInternalServerError(TestContext context) throws InterruptedException {
        try {
            expansionHandler = new ExpansionHandler(vertx, storage, httpClient, new HashMap<>(), ROOT, RULES_ROOT);

            Buffer collection = Buffer.buffer("{\"collection\":[\"a\"]}");
            String failureMessage = "child response body failed";
            CountDownLatch childResponseReceived = new CountDownLatch(1);
            AtomicReference<Handler<Throwable>> responseExceptionHandler = new AtomicReference<>();

            HttpClientResponse brokenResponse = Mockito.mock(HttpClientResponse.class);
            when(brokenResponse.statusCode()).thenReturn(200);
            when(brokenResponse.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
            doAnswer(inv -> {
                responseExceptionHandler.set(inv.getArgument(0));
                return brokenResponse;
            }).when(brokenResponse).exceptionHandler(any());

            HttpClientRequest childRequest = Mockito.mock(HttpClientRequest.class);
            when(childRequest.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
            doAnswer(inv -> {
                ((Handler<AsyncResult<HttpClientResponse>>) inv.getArgument(0))
                        .handle(Future.succeededFuture(brokenResponse));
                childResponseReceived.countDown();
                return null;
            }).when(childRequest).send(any(Handler.class));

            when(httpClient.request(eq(HttpMethod.GET), anyString())).thenAnswer(invocation -> {
                String uri = invocation.getArgument(1);
                return Future.succeededFuture(
                        "/some/collection/".equals(uri) ? successfulRequest(collection) : childRequest);
            });

            MultiMap params = MultiMap.caseInsensitiveMultiMap();
            params.set(EXPAND_PARAM, "1");
            HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
            when(response.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());

            expansionHandler.handleExpansionRecursion(
                    new Request(HttpMethod.GET, "/some/collection/", params, response));

            context.assertTrue(childResponseReceived.await(2, TimeUnit.SECONDS),
                    "the child response should be received");
            context.assertNotNull(responseExceptionHandler.get(),
                    "an exception handler must be registered on the child response");
            responseExceptionHandler.get().handle(new IllegalStateException(failureMessage));

            verify(response, timeout(2000))
                    .setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
            verify(response, timeout(2000)).end(failureMessage);
        } finally {
            vertx.close();
        }
    }

    @Test
    public void testStorageExpandRequestFutureFailureEndsExpansionWithInternalServerError(TestContext context) throws InterruptedException {
        try {
            expansionHandler = new ExpansionHandler(vertx, storage, httpClient, new HashMap<>(), ROOT, RULES_ROOT);

            Rule storageExpandRule = new Rule();
            storageExpandRule.setUrlPattern("/some/collection/");
            storageExpandRule.setStorageExpand(true);
            expansionHandler.rulesChanged(new ArrayList<>(List.of(storageExpandRule)));

            String failureMessage = "storage expand request failed";
            HttpClientRequest rootRequest = successfulRequest(Buffer.buffer("{\"collection\":[\"a\",\"b\"]}"));
            when(httpClient.request(eq(HttpMethod.GET), anyString()))
                    .thenReturn(Future.succeededFuture(rootRequest));
            when(httpClient.request(eq(HttpMethod.POST), eq("/some/collection/?storageExpand=true")))
                    .thenReturn(Future.failedFuture(failureMessage));

            MultiMap params = MultiMap.caseInsensitiveMultiMap();
            params.set(EXPAND_PARAM, "1");
            HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
            when(response.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());

            expansionHandler.handleExpansionRecursion(
                    new Request(HttpMethod.GET, "/some/collection/", params, response));

            verify(response, timeout(2000))
                    .setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
            verify(response, timeout(2000)).end(failureMessage);
        } finally {
            vertx.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testStorageExpandResponseBodyFailureEndsExpansionWithInternalServerError(TestContext context) throws InterruptedException {
        try {
            expansionHandler = new ExpansionHandler(vertx, storage, httpClient, new HashMap<>(), ROOT, RULES_ROOT);

            Rule storageExpandRule = new Rule();
            storageExpandRule.setUrlPattern("/some/collection/");
            storageExpandRule.setStorageExpand(true);
            expansionHandler.rulesChanged(new ArrayList<>(List.of(storageExpandRule)));

            String failureMessage = "storage expand response body failed";
            CountDownLatch storageResponseReceived = new CountDownLatch(1);
            AtomicReference<Handler<Throwable>> responseExceptionHandler = new AtomicReference<>();

            HttpClientResponse brokenResponse = Mockito.mock(HttpClientResponse.class);
            when(brokenResponse.statusCode()).thenReturn(200);
            when(brokenResponse.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
            doAnswer(inv -> {
                responseExceptionHandler.set(inv.getArgument(0));
                return brokenResponse;
            }).when(brokenResponse).exceptionHandler(any());

            HttpClientRequest storageRequest = Mockito.mock(HttpClientRequest.class);
            when(storageRequest.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
            doAnswer(inv -> {
                ((Handler<AsyncResult<HttpClientResponse>>) inv.getArgument(0))
                        .handle(Future.succeededFuture(brokenResponse));
                storageResponseReceived.countDown();
                return null;
            }).when(storageRequest).send(any(Handler.class));

            HttpClientRequest rootRequest = successfulRequest(Buffer.buffer("{\"collection\":[\"a\",\"b\"]}"));
            when(httpClient.request(eq(HttpMethod.GET), anyString()))
                    .thenReturn(Future.succeededFuture(rootRequest));
            when(httpClient.request(eq(HttpMethod.POST), eq("/some/collection/?storageExpand=true")))
                    .thenReturn(Future.succeededFuture(storageRequest));

            MultiMap params = MultiMap.caseInsensitiveMultiMap();
            params.set(EXPAND_PARAM, "1");
            HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
            when(response.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());

            expansionHandler.handleExpansionRecursion(
                    new Request(HttpMethod.GET, "/some/collection/", params, response));

            context.assertTrue(storageResponseReceived.await(2, TimeUnit.SECONDS),
                    "the storage expand response should be received");
            context.assertNotNull(responseExceptionHandler.get(),
                    "an exception handler must be registered on the storage expand response");
            responseExceptionHandler.get().handle(new IllegalStateException(failureMessage));

            verify(response, timeout(2000))
                    .setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
            verify(response, timeout(2000)).end(failureMessage);
        } finally {
            vertx.close();
        }
    }

    @Test
    public void testRootRequestFutureFailureEndsExpansionWithInternalServerError(TestContext context) throws InterruptedException {
        try {
            expansionHandler = new ExpansionHandler(vertx, storage, httpClient, new HashMap<>(), ROOT, RULES_ROOT);

            String failureMessage = "root request failed";
            when(httpClient.request(eq(HttpMethod.GET), anyString()))
                    .thenReturn(Future.failedFuture(failureMessage));

            MultiMap params = MultiMap.caseInsensitiveMultiMap();
            params.set(EXPAND_PARAM, "1");
            HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
            when(response.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());

            expansionHandler.handleExpansionRecursion(
                    new Request(HttpMethod.GET, "/some/collection/", params, response));

            verify(response, timeout(2000))
                    .setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
            verify(response, timeout(2000)).end(failureMessage);
        } finally {
            vertx.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testInitialCollectionSendFailureEndsExpansionWithInternalServerError(TestContext context) throws InterruptedException {
        try {
            expansionHandler = new ExpansionHandler(vertx, storage, httpClient, new HashMap<>(), ROOT, RULES_ROOT);

            String failureMessage = "initial collection send failed";
            CountDownLatch sendFailureDelivered = new CountDownLatch(1);
            AtomicReference<RuntimeException> sendCallbackException = new AtomicReference<>();

            HttpClientRequest initialRequest = Mockito.mock(HttpClientRequest.class);
            when(initialRequest.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
            doAnswer(invocation -> {
                Handler<AsyncResult<HttpClientResponse>> sendHandler = invocation.getArgument(0);
                vertx.runOnContext(ignored -> {
                    try {
                        sendHandler.handle(Future.failedFuture(failureMessage));
                    } catch (RuntimeException exception) {
                        sendCallbackException.set(exception);
                    } finally {
                        sendFailureDelivered.countDown();
                    }
                });
                return null;
            }).when(initialRequest).send(any(Handler.class));
            when(httpClient.request(eq(HttpMethod.GET), anyString()))
                    .thenReturn(Future.succeededFuture(initialRequest));

            MultiMap params = MultiMap.caseInsensitiveMultiMap();
            params.set(EXPAND_PARAM, "1");
            HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
            when(response.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());

            expansionHandler.handleExpansionRecursion(
                    new Request(HttpMethod.GET, "/some/collection/", params, response));

            context.assertTrue(sendFailureDelivered.await(2, TimeUnit.SECONDS),
                    "the initial collection send failure should be delivered asynchronously");
            context.assertNull(sendCallbackException.get(),
                    "the initial collection send failure callback must not throw: " + sendCallbackException.get());
            verify(response, timeout(2000))
                    .setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
            verify(response, timeout(2000)).end(failureMessage);
        } finally {
            vertx.close();
        }
    }

    @Test
    public void testStorageExpandDepthLimitRejectsQueryBearingRequest(TestContext context) {
        try {
            expansionHandler = new ExpansionHandler(vertx, storage, httpClient, new HashMap<>(), ROOT, RULES_ROOT);

            Rule storageExpandRule = new Rule();
            storageExpandRule.setUrlPattern("/some/collection/");
            storageExpandRule.setStorageExpand(true);
            expansionHandler.rulesChanged(new ArrayList<>(List.of(storageExpandRule)));

            HttpClientRequest rootRequest = successfulRequest(
                    Buffer.buffer("{\"collection\":[\"a\"]}"));
            when(httpClient.request(eq(HttpMethod.GET), anyString()))
                    .thenReturn(Future.succeededFuture(rootRequest));
            HttpClientRequest storageRequest = successfulRequest(Buffer.buffer("{\"a\":{}}"));
            when(httpClient.request(eq(HttpMethod.POST), eq("/some/collection/?storageExpand=true")))
                    .thenReturn(Future.succeededFuture(storageRequest));

            MultiMap params = MultiMap.caseInsensitiveMultiMap();
            params.set(EXPAND_PARAM, "2");
            HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
            when(response.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());

            expansionHandler.handleExpansionRecursion(
                    new Request(HttpMethod.GET, "/some/collection/?expand=2", params, response));

            verify(response, timeout(2000))
                    .setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
            verify(response, timeout(2000))
                    .end("Expand values higher than 1 are not supported for storageExpand requests");
            verifyNoInteractions(httpClient);
        } finally {
            vertx.close();
        }
    }

    @SuppressWarnings("unchecked")
    private HttpClientRequest successfulRequest(Buffer body) {
        HttpClientResponse cRes = Mockito.mock(HttpClientResponse.class);
        when(cRes.statusCode()).thenReturn(200);
        when(cRes.statusMessage()).thenReturn("OK");
        when(cRes.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        doAnswer(inv -> {
            ((Handler<Buffer>) inv.getArgument(0)).handle(body);
            return cRes;
        }).when(cRes).bodyHandler(any());

        HttpClientRequest cReq = Mockito.mock(HttpClientRequest.class);
        when(cReq.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        doAnswer(inv -> {
            ((Handler<AsyncResult<HttpClientResponse>>) inv.getArgument(0)).handle(Future.succeededFuture(cRes));
            return null;
        }).when(cReq).send(any(Handler.class));
        return cReq;
    }

    @Test
    public void testIsCollection(TestContext context) {
        expansionHandler = new ExpansionHandler(vertx, storage, httpClient, new HashMap<>(), ROOT, RULES_ROOT);
        context.assertFalse(expansionHandler.isCollection("/some/collection/uri"));
        context.assertTrue(expansionHandler.isCollection("/some/collection/"));
    }

    @Test
    public void testStorageExpandRequestWithExpandLevelLimitExceeded(TestContext context) {
        expansionHandler = new ExpansionHandler(vertx, storage, httpClient, new HashMap<>(), ROOT, RULES_ROOT);

        List<Rule> rules = new ArrayList<>();
        Rule rule1 = new Rule();
        rule1.setUrlPattern("/test/rules/rule/storageExpand");
        rule1.setStorageExpand(true);
        rules.add(rule1);
        expansionHandler.rulesChanged(rules);

        MultiMap params = MultiMap.caseInsensitiveMultiMap();
        params.set(EXPAND_PARAM, "2");
        HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
        Request request = new Request(HttpMethod.GET, "/test/rules/rule/storageExpand", params, response);

        expansionHandler.handleExpansionRecursion(request);

        verify(response, times(1)).setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
        verify(response, times(1)).setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
        verify(response, times(1)).end("Expand values higher than 1 are not supported for storageExpand requests");
    }

    @Test
    public void testBadRequestResponseForInvalidExpandParameterRequests(TestContext context) {
        expansionHandler = new ExpansionHandler(vertx, storage, httpClient, new HashMap<>(), ROOT, RULES_ROOT);

        MultiMap params = MultiMap.caseInsensitiveMultiMap();
        params.set(EXPAND_PARAM, "foo");
        HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
        Request request = new Request(HttpMethod.GET, "/some/expandRequest/uri", params, response);

        expansionHandler.handleExpansionRecursion(request);

        verify(response, times(1)).setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
        verify(response, times(1)).setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
        verify(response, times(1)).end("Expand parameter is not valid. Must be a positive number");

        verifyNoInteractions(httpClient);
    }

    @Test
    public void testBadRequestResponseForExceedingMaxExpansionLevelHard(TestContext context) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("max.expansion.level.hard", "10");
        expansionHandler = new ExpansionHandler(vertx, storage, httpClient, properties, ROOT, RULES_ROOT);

        MultiMap params = MultiMap.caseInsensitiveMultiMap();
        params.set(EXPAND_PARAM, "15");
        HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
        Request request = new Request(HttpMethod.GET, "/some/expandRequest/uri", params, response);

        expansionHandler.handleExpansionRecursion(request);

        verify(response, times(1)).setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
        verify(response, times(1)).setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
        verify(response, times(1)).end("Expand level '15' is greater than the maximum expand level '10'");

        verifyNoInteractions(httpClient);
    }

    private static class Request extends DummyHttpServerRequest {
        private MultiMap params;
        private HttpMethod httpMethod;
        private String uri;
        private HttpServerResponse response;

        public Request(HttpMethod httpMethod, String uri, MultiMap params) {
            this(httpMethod, uri, params, null);
        }

        public Request(HttpMethod httpMethod, String uri, MultiMap params, HttpServerResponse response) {
            this.httpMethod = httpMethod;
            this.uri = uri;
            this.params = params;
            this.response = response;
        }

        @Override public HttpMethod method() {
            return httpMethod;
        }
        @Override public String uri() {
            return uri;
        }
        @Override public MultiMap params() { return params; }

        @Override public HttpServerResponse response() {return response; }

        @Override
        public MultiMap headers() { return MultiMap.caseInsensitiveMultiMap(); }

        @Override
        public String path() { return uri.split("\\?")[0]; }

        @Override
        public HttpServerRequest handler(Handler<Buffer> handler) { return this; }

        @Override
        public HttpServerRequest endHandler(Handler<Void> endHandler) {
            endHandler.handle(null);
            return this;
        }

        @Override
        public HttpServerRequest pause() { return this; }

        @Override
        public HttpServerRequest resume() { return this; }
    }
}

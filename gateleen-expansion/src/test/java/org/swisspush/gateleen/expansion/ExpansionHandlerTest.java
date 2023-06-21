package org.swisspush.gateleen.expansion;

import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
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
    //    Mockito.when(httpClient.request(any(HttpMethod.class), anyString(), Matchers.<Handler<HttpClientResponse>>any())).thenReturn(Mockito.mock(HttpClientRequest.class));
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

        verifyZeroInteractions(httpClient);
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

        verifyZeroInteractions(httpClient);
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
        public HttpServerRequest pause() { return this; }

        @Override
        public HttpServerRequest resume() { return this; }
    }
}

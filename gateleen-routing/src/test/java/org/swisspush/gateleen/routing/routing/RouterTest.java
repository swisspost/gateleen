package org.swisspush.gateleen.routing.routing;

import org.swisspush.gateleen.core.logging.LoggingResource;
import org.swisspush.gateleen.core.logging.LoggingResourceManager;
import org.swisspush.gateleen.core.monitoring.MonitoringHandler;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.StatusCode;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.shareddata.LocalMap;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.redis.RedisClient;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.security.cert.X509Certificate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Tests for the Router class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class RouterTest {

    private Map<String, String> localStorageValues;
    private Vertx vertx;
    private Map<String, Object> properties;
    private LoggingResourceManager loggingResourceManager;
    private MonitoringHandler monitoringHandler;
    private RedisClient redisClient;
    private HttpClient httpClient;
    private String serverUrl;
    private String rulesPath;
    private String userProfilePath;
    private String randomResourcePath;
    private JsonObject info;
    private LocalMap<String, Object> routerStateMap;

    private final String RULES_WITH_MISSING_PROPS = "{\n"
            + "  \"/gateleen/rule/1\": {\n"
            + "    \"description\": \"Test Rule 1\",\n"
            + "    \"url\": \"${gateleen.test.prop.1}/gateleen/rule/1\"\n"
            + "  },\n"
            + "  \"/gateleen/rule/2\": {\n"
            + "    \"description\": \"Test Rule 2\",\n"
            + "    \"url\": \"${gateleen.test.prop.2}/gateleen/rule/2\"\n"
            + "  }\n"
            + "}";

    private final String RULES_WITH_VALID_PROPS = "{\n"
            + "  \"/gateleen/rule/1\": {\n"
            + "    \"description\": \"Test Rule 1\",\n"
            + "    \"url\": \"${gateleen.test.prop.valid}/gateleen/rule/1\"\n"
            + "  },\n"
            + "  \"/gateleen/rule/2\": {\n"
            + "    \"description\": \"Test Rule 2\",\n"
            + "    \"url\": \"${gateleen.test.prop.valid}/gateleen/rule/2\"\n"
            + "  }\n"
            + "}";

    private final String RANDOM_RESOURCE = "{\n"
            + "  \"randomkey1\": 123,\n"
            + "  \"randomkey2\": 456\n"
            + "}";

    @Before
    public void setUp(){
        // setup
        vertx = Mockito.mock(Vertx.class);
        Mockito.when(vertx.eventBus()).thenReturn(Mockito.mock(EventBus.class));
        Mockito.when(vertx.createHttpClient()).thenReturn(Mockito.mock(HttpClient.class));

        properties = new HashMap<>();
        properties.put("gateleen.test.prop.valid", "http://someserver/");
        loggingResourceManager = Mockito.mock(LoggingResourceManager.class);
        Mockito.when(loggingResourceManager.getLoggingResource()).thenReturn(new LoggingResource());
        redisClient = Mockito.mock(RedisClient.class);
        monitoringHandler = Mockito.mock(MonitoringHandler.class);
        httpClient = Mockito.mock(HttpClient.class);

        serverUrl = "/gateleen/server";
        rulesPath = serverUrl + "/admin/v1/routing/rules";
        randomResourcePath = serverUrl + "/random/resource";
        userProfilePath = serverUrl + "/users/v1/%s/profile";
        info = new JsonObject();

        localStorageValues = new HashMap<String, String>(){{
            put(rulesPath, RULES_WITH_MISSING_PROPS);
            put(randomResourcePath, RANDOM_RESOURCE);
        }};

        routerStateMap = new DummyLocalMap<>();
    }

    @Test
    public void testRouterConstructionValidConfiguration(TestContext context){
        properties.put("gateleen.test.prop.1", "http://someserver/");
        properties.put("gateleen.test.prop.2", "http://someserver/");
        Router router = new Router(vertx, routerStateMap, new MockResourceStorage(), properties, loggingResourceManager, monitoringHandler, httpClient, serverUrl, rulesPath, userProfilePath, info);
        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");
    }

    @Test
    public void testRouterConstructionWithMissingProperty(TestContext context){
        Router router = new Router(vertx, routerStateMap, new MockResourceStorage(), properties, loggingResourceManager, monitoringHandler, httpClient, serverUrl, rulesPath, userProfilePath, info);
        context.assertTrue(router.isRoutingBroken(), "Routing should be broken because of missing properties entry");
        context.assertNotNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");
        context.assertTrue(router.getRoutingBrokenMessage().contains("gateleen.test.prop.1"), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");
    }

    @Test
    public void testFixBrokenRouting(TestContext context){
        Router router = new Router(vertx, routerStateMap, new MockResourceStorage(), properties, loggingResourceManager, monitoringHandler, httpClient, serverUrl, rulesPath, userProfilePath, info);
        context.assertTrue(router.isRoutingBroken(), "Routing should be broken because of missing properties entry");
        context.assertNotNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");
        context.assertTrue(router.getRoutingBrokenMessage().contains("gateleen.test.prop.1"), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");

        // fix routing by passing a valid rules resource via PUT request
        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        class UpdateRulesWithValidResourceRequest extends DummyHttpServerRequest{
            @Override public HttpMethod method() {
                return HttpMethod.PUT;
            }

            @Override public String uri() {
                return "/gateleen/server/admin/v1/routing/rules";
            }

            @Override
            public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
                bodyHandler.handle(Buffer.buffer(RULES_WITH_VALID_PROPS));
                return this;
            }

            @Override
            public HttpServerResponse response() {
                return response;
            }
        }

        router.route(new UpdateRulesWithValidResourceRequest());
        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken anymore");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");
    }

    @Test
    public void testGETRoutingRulesWithBrokenRouterShouldWork(TestContext context){
        Router router = new Router(vertx, routerStateMap, new MockResourceStorage(), properties, loggingResourceManager, monitoringHandler, httpClient, serverUrl, rulesPath, userProfilePath, info);
        context.assertTrue(router.isRoutingBroken(), "Routing should be broken because of missing properties entry");
        context.assertNotNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");
        context.assertTrue(router.getRoutingBrokenMessage().contains("gateleen.test.prop.1"), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");

        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        class GETRoutingRulesRequest extends DummyHttpServerRequest{
            @Override public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override public String uri() {
                return "/gateleen/server/admin/v1/routing/rules";
            }

            @Override
            public DummyHttpServerResponse response() {
                return response;
            }
        }
        GETRoutingRulesRequest request = new GETRoutingRulesRequest();
        router.route(request);
        context.assertTrue(router.isRoutingBroken(), "Routing should still be broken");
        context.assertNotNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");
        context.assertTrue(router.getRoutingBrokenMessage().contains("gateleen.test.prop.1"), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");

        context.assertEquals(StatusCode.OK.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 200");
        context.assertEquals(StatusCode.OK.getStatusMessage(), request.response().getStatusMessage(), "StatusMessage should be OK");
        context.assertEquals(RULES_WITH_MISSING_PROPS, request.response().getResultBuffer(), "RoutingRules should be returned as result");
    }

    @Test
    public void testGETAnyResourceWithBrokenRouterShouldNotWork(TestContext context){
        Router router = new Router(vertx, routerStateMap, new MockResourceStorage(), properties, loggingResourceManager, monitoringHandler, httpClient, serverUrl, rulesPath, userProfilePath, info);
        context.assertTrue(router.isRoutingBroken(), "Routing should be broken because of missing properties entry");
        context.assertNotNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");
        context.assertTrue(router.getRoutingBrokenMessage().contains("gateleen.test.prop.1"), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");

        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        class GETRandomResourceRequest extends DummyHttpServerRequest{
            @Override public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override public String uri() {
                return "/gateleen/server/random/resource";
            }

            @Override
            public DummyHttpServerResponse response() {
                return response;
            }
        }
        GETRandomResourceRequest request = new GETRandomResourceRequest();
        router.route(request);
        context.assertTrue(router.isRoutingBroken(), "Routing should still be broken");
        context.assertNotNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");
        context.assertTrue(router.getRoutingBrokenMessage().contains("gateleen.test.prop.1"), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");

        context.assertEquals(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 500");
        context.assertEquals(StatusCode.INTERNAL_SERVER_ERROR.getStatusMessage(), request.response().getStatusMessage(), "StatusMessage should be Internal Server Error");
        context.assertTrue(request.response().getResultBuffer().contains("Routing is broken"), "Routing is broken message should be returned");
        context.assertTrue(request.response().getResultBuffer().contains("gateleen.test.prop.1"), "The message should contain 'gateleen.test.prop.1' in the message");
    }

    @Test
    public void testGETAnyResourceAfterFixingBrokenRouterShouldWorkAgain(TestContext context){
        Router router = new Router(vertx, routerStateMap, new MockResourceStorage(), properties, loggingResourceManager, monitoringHandler, httpClient, serverUrl, rulesPath, userProfilePath, info);
        context.assertTrue(router.isRoutingBroken(), "Routing should be broken because of missing properties entry");
        context.assertNotNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");
        context.assertTrue(router.getRoutingBrokenMessage().contains("gateleen.test.prop.1"), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");

        // get random resource with broken routing. Should not work
        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        class GETRandomResourceRequest extends DummyHttpServerRequest{
            @Override public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override public String uri() {
                return "/gateleen/server/random/resource";
            }

            @Override public String path() {
                return "/gateleen/server/random/resource";
            }

            @Override
            public DummyHttpServerResponse response() {
                return response;
            }
        }
        GETRandomResourceRequest request = new GETRandomResourceRequest();
        router.route(request);
        context.assertTrue(router.isRoutingBroken(), "Routing should still be broken");
        context.assertNotNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");
        context.assertTrue(router.getRoutingBrokenMessage().contains("gateleen.test.prop.1"), "RoutingBrokenMessage should contain 'gateleen.test.prop.1' property");

        context.assertEquals(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 500");
        context.assertEquals(StatusCode.INTERNAL_SERVER_ERROR.getStatusMessage(), request.response().getStatusMessage(), "StatusMessage should be Internal Server Error");
        context.assertTrue(request.response().getResultBuffer().contains("Routing is broken"), "Routing is broken message should be returned");
        context.assertTrue(request.response().getResultBuffer().contains("gateleen.test.prop.1"), "The message should contain 'gateleen.test.prop.1' in the message");

        // fix routing
        final DummyHttpServerResponse responseFix = new DummyHttpServerResponse();
        class UpdateRulesWithValidResourceRequest extends DummyHttpServerRequest{
            @Override public HttpMethod method() {
                return HttpMethod.PUT;
            }

            @Override public String uri() {
                return "/gateleen/server/admin/v1/routing/rules";
            }

            @Override
            public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
                bodyHandler.handle(Buffer.buffer(RULES_WITH_VALID_PROPS));
                return this;
            }

            @Override
            public HttpServerResponse response() {
                return responseFix;
            }
        }
        router.route(new UpdateRulesWithValidResourceRequest());
        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken anymore");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");

        // retry get random resource. Should work now
        final DummyHttpServerResponse responseRandomResource = new DummyHttpServerResponse();
        class GETRandomResourceAgainRequest extends DummyHttpServerRequest{
            @Override public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override public String uri() {
                return "/gateleen/server/random/resource";
            }

            @Override public String path() { return "/gateleen/server/random/resource"; }

            @Override public MultiMap params() { return new CaseInsensitiveHeaders(); }

            @Override public MultiMap headers() { return new CaseInsensitiveHeaders(); }

            @Override
            public DummyHttpServerResponse response() {
                return responseRandomResource;
            }
        }
        GETRandomResourceAgainRequest requestRandomResource = new GETRandomResourceAgainRequest();
        router.route(requestRandomResource);
        context.assertFalse(router.isRoutingBroken(), "Routing should not be broken anymore");
        context.assertNull(router.getRoutingBrokenMessage(), "RoutingBrokenMessage should be null");
    }

    class MockResourceStorage implements ResourceStorage {

        @Override
        public void get(String path, Handler<Buffer> bodyHandler) {
            String result = localStorageValues.get(path);
            if(result != null) {
                bodyHandler.handle(Buffer.buffer(result));
            } else {
                bodyHandler.handle(null);
            }
        }

        @Override
        public void put(String uri, MultiMap headers, Buffer buffer, Handler<Integer> doneHandler) {

        }

        @Override
        public void put(String uri, Buffer buffer, Handler<Integer> doneHandler) {
            localStorageValues.put(uri, buffer.toString());
            doneHandler.handle(200);
        }

        @Override
        public void delete(String uri, Handler<Integer> doneHandler) {

        }
    }

    class DummyHttpServerRequest implements HttpServerRequest {

        @Override public HttpVersion version() {
            return null;
        }

        @Override public HttpMethod method() {
            return null;
        }

        @Override public String uri() {
            return null;
        }

        @Override public String path() {
            return "";
        }

        @Override public String query() {
            return null;
        }

        @Override public HttpServerResponse response() {
            return null;
        }

        @Override public MultiMap headers() {
            return null;
        }

        @Override public String getHeader(String headerName) { return null; }

        @Override public String getHeader(CharSequence headerName) { return null; }

        @Override public MultiMap params() {
            return null;
        }

        @Override public String getParam(String paramName) { return null; }

        @Override public SocketAddress remoteAddress() {
            return null;
        }

        @Override public SocketAddress localAddress() {
            return null;
        }

        @Override public X509Certificate[] peerCertificateChain() throws SSLPeerUnverifiedException {
            return new X509Certificate[0];
        }

        @Override public String absoluteURI() {
            return null;
        }

        @Override public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
            return null;
        }

        @Override public NetSocket netSocket() {
            return null;
        }

        @Override public HttpServerRequest setExpectMultipart(boolean expect) { return null; }

        @Override public boolean isExpectMultipart() { return false; }

        @Override public HttpServerRequest uploadHandler(Handler<HttpServerFileUpload> uploadHandler) {
            return null;
        }

        @Override public MultiMap formAttributes() {
            return null;
        }

        @Override public String getFormAttribute(String attributeName) { return null; }

        @Override public ServerWebSocket upgrade() { return null; }

        @Override public boolean isEnded() { return false; }

        @Override public HttpServerRequest endHandler(Handler<Void> endHandler) {
            return null;
        }

        @Override public HttpServerRequest handler(Handler<Buffer> handler) {
            return null;
        }

        @Override public HttpServerRequest pause() {
            return null;
        }

        @Override public HttpServerRequest resume() {
            return null;
        }

        @Override public HttpServerRequest exceptionHandler(Handler<Throwable> handler) {
            return null;
        }
    }

    class DummyHttpServerResponse implements HttpServerResponse{

        private int statusCode;
        private String statusMessage;
        private String resultBuffer;

        public String getResultBuffer(){
            return resultBuffer;
        }

        @Override public int getStatusCode() {
            return statusCode;
        }

        @Override public HttpServerResponse setStatusCode(int statusCode) {
            this.statusCode = statusCode;
            return this;
        }

        @Override public String getStatusMessage() {
            return statusMessage;
        }

        @Override public HttpServerResponse setStatusMessage(String statusMessage) {
            this.statusMessage = statusMessage;
            return this;
        }

        @Override public HttpServerResponse setChunked(boolean chunked) {
            return null;
        }

        @Override public boolean isChunked() {
            return false;
        }

        @Override public MultiMap headers() {
            return null;
        }

        @Override public HttpServerResponse putHeader(String name, String value) {
            return null;
        }

        @Override public HttpServerResponse putHeader(CharSequence name, CharSequence value) {
            return null;
        }

        @Override public HttpServerResponse putHeader(String name, Iterable<String> values) {
            return null;
        }

        @Override public HttpServerResponse putHeader(CharSequence name, Iterable<CharSequence> values) {
            return null;
        }

        @Override public MultiMap trailers() {
            return null;
        }

        @Override public HttpServerResponse putTrailer(String name, String value) {
            return null;
        }

        @Override public HttpServerResponse putTrailer(CharSequence name, CharSequence value) {
            return null;
        }

        @Override public HttpServerResponse putTrailer(String name, Iterable<String> values) {
            return null;
        }

        @Override public HttpServerResponse putTrailer(CharSequence name, Iterable<CharSequence> value) {
            return null;
        }

        @Override public HttpServerResponse closeHandler(Handler<Void> handler) {
            return null;
        }

        @Override public HttpServerResponse write(Buffer chunk) {
            return null;
        }

        @Override public HttpServerResponse write(String chunk, String enc) {
            return null;
        }

        @Override public HttpServerResponse write(String chunk) {
            return null;
        }

        @Override public HttpServerResponse writeContinue() { return null; }

        @Override public void end(String chunk) {
            this.resultBuffer = chunk;
        }

        @Override public void end(String chunk, String enc) {}

        @Override public void end(Buffer chunk) {
            this.resultBuffer = chunk.toString();
        }

        @Override public void end() {}

        @Override public HttpServerResponse sendFile(String filename) {
            return null;
        }

        @Override public HttpServerResponse sendFile(String filename, long offset, long length) {
            return null;
        }

        @Override public HttpServerResponse sendFile(String filename, Handler<AsyncResult<Void>> resultHandler) {
            return null;
        }

        @Override
        public HttpServerResponse sendFile(String filename, long offset, long length, Handler<AsyncResult<Void>> resultHandler) {
            return null;
        }

        @Override public void close() {}

        @Override public boolean ended() { return false; }

        @Override public boolean closed() { return false; }

        @Override public boolean headWritten() { return false; }

        @Override public HttpServerResponse headersEndHandler(Handler<Void> handler) { return null; }

        @Override public HttpServerResponse bodyEndHandler(Handler<Void> handler) { return null; }

        @Override public long bytesWritten() { return 0; }

        @Override public HttpServerResponse setWriteQueueMaxSize(int maxSize) {
            return null;
        }

        @Override public boolean writeQueueFull() {
            return false;
        }

        @Override public HttpServerResponse drainHandler(Handler<Void> handler) {
            return null;
        }

        @Override public HttpServerResponse exceptionHandler(Handler<Throwable> handler) {
            return null;
        }
    }

    private class DummyLocalMap<K, V> implements LocalMap<String, Object> {

        private Map<String, Object> map = new HashMap<>();

        @Override
        public Object get(String key) {
            return map.get(key);
        }

        @Override
        public Object put(String key, Object value) {
            return map.put(key, value);
        }

        @Override
        public Object remove(String key) {
            return map.remove(key);
        }

        @Override
        public void clear() {
            map.clear();
        }

        @Override
        public int size() {
            return map.size();
        }

        @Override
        public boolean isEmpty() {
            return map.isEmpty();
        }

        @Override
        public Object putIfAbsent(String key, Object value) {
            if(!map.containsKey(key)){
                map.put(key, value);
                return null;
            }
            return map.get(key);
        }

        @Override
        public boolean removeIfPresent(String key, Object value) {
            if(map.containsKey(key)){
                map.remove(key);
                return true;
            }
            return false;
        }

        @Override
        public boolean replaceIfPresent(String key, Object oldValue, Object newValue) {
            if(map.containsKey(key) && map.get(key).equals(oldValue)){
                map.put(key, newValue);
                return true;
            }
            return false;
        }

        @Override
        public Object replace(String key, Object value) {
            return map.replace(key, value);
        }

        @Override
        public void close() {}

        @Override
        public Set<String> keySet() {
            return map.keySet();
        }

        @Override
        public Collection<Object> values() {
            return map.values();
        }
    }

}

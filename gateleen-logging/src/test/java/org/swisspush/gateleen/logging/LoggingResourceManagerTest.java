package org.swisspush.gateleen.logging;

import com.google.common.collect.ImmutableMap;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.buffer.impl.BufferImpl;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.http.DummyHttpServerResponse;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.util.StatusCode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Tests for the {@link LoggingResourceManager} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class LoggingResourceManagerTest {

    private static final String TYPE = "type";
    private static final String FILE = "file";
    private static final String METADATA = "metadata";
    private static final String TRANSMISSION = "transmission";
    private static final String ADDRESS = "address";
    private static final String EVENT_BUS = "eventBus";
    private static final Logger logger = LoggerFactory.getLogger( LoggingResourceManagerTest.class );
    private Vertx vertx;
    private ResourceStorage storage;

    private final String LOGGING_URI = "/playground/server/admin/v1/logging";

    private final String INITIAL_LOGGING_RESOURCE = ResourcesUtils.loadResource("testresource_inital_logging_resource", true);
    private final String VALID_LOGGING_RESOURCE = ResourcesUtils.loadResource("testresource_valid_logging_resource", true);
    private final String INVALID_TYPE_LOGGING_RESOURCE = ResourcesUtils.loadResource("testresource_invalid_type_logging_resource", true);

    @Before
    public void setUp(){
        vertx = Mockito.mock(Vertx.class);
        Mockito.when(vertx.eventBus()).thenReturn(Mockito.mock(EventBus.class));
        storage = new MockResourceStorage(ImmutableMap.of(LOGGING_URI, INITIAL_LOGGING_RESOURCE));
    }

    @Test
    public void testLoggingResourceContent(TestContext context){
        storage = new MockResourceStorage(ImmutableMap.of(LOGGING_URI, VALID_LOGGING_RESOURCE));
        LoggingResourceManager manager = new LoggingResourceManager(vertx, storage, LOGGING_URI);
        LoggingResource loggingResource = manager.getLoggingResource();

        // PayloadFilters
        List<Map<String, String>> payloadFilterEntries = loggingResource.getPayloadFilters();
        context.assertEquals(2, payloadFilterEntries.size());

        // DestinationEntries
        Map<String, Map<String, String>> destinationEntries = loggingResource.getDestinationEntries();
        context.assertEquals(2, destinationEntries.size());
        context.assertTrue(destinationEntries.containsKey("fileLog"));
        Map<String, String> fileLogProperties = destinationEntries.get("fileLog");
        assertFilterProperty(context, fileLogProperties, TYPE, FILE);
        assertFilterProperty(context, fileLogProperties, FILE, "requests.log");
        context.assertFalse(fileLogProperties.containsKey(METADATA));
        context.assertFalse(fileLogProperties.containsKey(TRANSMISSION));
        context.assertTrue(destinationEntries.containsKey("eventBusLog"));
        Map<String, String> eventBusLogProperties = destinationEntries.get("eventBusLog");
        assertFilterProperty(context, eventBusLogProperties, TYPE, EVENT_BUS);
        assertFilterProperty(context, eventBusLogProperties, ADDRESS, "some_eventbus_address");
        assertFilterProperty(context, eventBusLogProperties, METADATA, "meta 1");
        assertFilterProperty(context, eventBusLogProperties, TRANSMISSION, "send");
    }

    @Test
    public void testValidContentShouldBeStoredInStorage(TestContext context){
        Async async = context.async();
        LoggingResourceManager manager = new LoggingResourceManager(vertx, storage, LOGGING_URI);

        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        class UpdateLoggingResourceWithValidDataRequest extends LoggingResourcePUTRequest {
            @Override
            public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
                bodyHandler.handle(Buffer.buffer(VALID_LOGGING_RESOURCE));
                return this;
            }

            @Override
            public HttpServerResponse response() {
                return response;
            }
        }

        storage.get(LOGGING_URI, result -> {
            context.assertEquals(INITIAL_LOGGING_RESOURCE, result.toString());
            context.assertTrue(manager.handleLoggingResource(new UpdateLoggingResourceWithValidDataRequest()));
            storage.get(LOGGING_URI, updatedResult -> {
                context.assertEquals(VALID_LOGGING_RESOURCE, updatedResult.toString());
                async.complete();
            });
        });
    }

    @Test
    public void testInvalidContentShouldNotBeStoredInStorage(TestContext context){
        Async async = context.async();
        LoggingResourceManager manager = new LoggingResourceManager(vertx, storage, LOGGING_URI);

        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        class UpdateLoggingResourceWithValidDataRequest extends LoggingResourcePUTRequest {
            @Override
            public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
                bodyHandler.handle(Buffer.buffer(INVALID_TYPE_LOGGING_RESOURCE));
                return this;
            }

            @Override
            public HttpServerResponse response() {
                return response;
            }
        }

        storage.get(LOGGING_URI, result -> {
            context.assertEquals(INITIAL_LOGGING_RESOURCE, result.toString());
            context.assertTrue(manager.handleLoggingResource(new UpdateLoggingResourceWithValidDataRequest()));
            storage.get(LOGGING_URI, updatedResult -> {
                context.assertEquals(INITIAL_LOGGING_RESOURCE, updatedResult.toString(), "The logging resource in the storage should still be equal to the initial resource");
                context.assertEquals(StatusCode.BAD_REQUEST.getStatusCode(), response.getStatusCode());
                async.complete();
            });
        });
    }

    @Test
    public void invalidRegexGetsRejected( TestContext testContext ) {
        // Wire up victim instance
        final String loggingUrl = "/houston/server/admin/v1/logging";
        final LoggingResourceManager loggingResourceManager = new LoggingResourceManager( vertx , storage , loggingUrl );

        // Mock a request
        final Integer[] responseStatusCode = new Integer[]{ null };
        final DummyHttpServerResponse httpServerResponse = new DummyHttpServerResponse(){
            @Override public synchronized HttpServerResponse setStatusCode( int statusCode ) {
                if( responseStatusCode[0] != null ) logger.debug("Status code "+responseStatusCode[0]+" got overridden with "+statusCode+".");
                // Keep status code to assert it later.
                responseStatusCode[0] = statusCode;
                return this;
            }
        };
        final HttpServerRequest request = new DummyHttpServerRequest(){
            @Override public String uri() {
                return loggingUrl;
            }
            @Override public HttpMethod method() {
                return HttpMethod.PUT;
            }
            @Override public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
                Buffer buffer = new BufferImpl();
                buffer.setBytes( 0 , ("{\n" +
                        "  \"payload\": {\n" +
                        "    \"filters\": [\n" +
                        "      {\n" +
                        "        \"url\": \"/houston/services/.*(?<!(all/position)$\",\n" +
                        "        \"method\": \"PUT|DELETE\"\n" +
                        "      }\n" +
                        "    ]\n" +
                        "  }\n" +
                        "}\n").getBytes(StandardCharsets.UTF_8));
                bodyHandler.handle( buffer );
                return null;
            }
            @Override public MultiMap headers() {
                return new CaseInsensitiveHeaders();
            }
            @Override public HttpServerResponse response() {
                return httpServerResponse;
            }
        };

        // Trigger victim to do its work.
        final boolean returnValue = loggingResourceManager.handleLoggingResource( request );

        // Assert victim accepted our request.
        testContext.assertTrue( returnValue );
        // did set a response code,
        testContext.assertNotNull( responseStatusCode[0] );
        // responded with a status code of 4xx,
        testContext.assertTrue( responseStatusCode[0] >= 400 );
        testContext.assertTrue( responseStatusCode[0] <= 499 );
        // and wrote a ValidationException to the body.
        testContext.assertTrue( httpServerResponse.getResultBuffer().startsWith("ValidationException: ") );
    }

    private void assertFilterProperty(TestContext context, Map<String, String> entries, String property, String value){
        context.assertTrue(entries.containsKey(property), "Entries should contain property '" + property + "'");
        context.assertEquals(entries.get(property), value, "Property value not as excpected. Should be '" + value + "'");
    }

    class LoggingResourcePUTRequest extends DummyHttpServerRequest{
        @Override public HttpMethod method() {
            return HttpMethod.PUT;
        }
        @Override public String uri() {
            return LOGGING_URI;
        }
        @Override public MultiMap headers() { return new CaseInsensitiveHeaders(); }
    }
}

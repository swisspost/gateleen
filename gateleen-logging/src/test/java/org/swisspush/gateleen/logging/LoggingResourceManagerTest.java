package org.swisspush.gateleen.logging;

import com.google.common.collect.ImmutableMap;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
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
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.http.DummyHttpServerResponse;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.util.StatusCode;

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
    private static final String ADDRESS = "address";
    private static final String EVENT_BUS = "eventBus";
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
        assertFilterProperty(context, fileLogProperties, METADATA, "");
        context.assertTrue(destinationEntries.containsKey("eventBusLog"));
        Map<String, String> eventBusLogProperties = destinationEntries.get("eventBusLog");
        assertFilterProperty(context, eventBusLogProperties, TYPE, EVENT_BUS);
        assertFilterProperty(context, eventBusLogProperties, ADDRESS, "some_eventbus_address");
        assertFilterProperty(context, eventBusLogProperties, METADATA, "meta 1");
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
    }
}

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

/**
 * Tests for the {@link LoggingResourceManager} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class LoggingResourceManagerTest {

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

    class LoggingResourcePUTRequest extends DummyHttpServerRequest{
        @Override public HttpMethod method() {
            return HttpMethod.PUT;
        }
        @Override public String uri() {
            return LOGGING_URI;
        }
    }
}

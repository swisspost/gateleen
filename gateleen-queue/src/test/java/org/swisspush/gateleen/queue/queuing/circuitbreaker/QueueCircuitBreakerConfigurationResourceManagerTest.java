package org.swisspush.gateleen.queue.queuing.circuitbreaker;

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
 * Tests for the {@link QueueCircuitBreakerConfigurationResourceManager} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class QueueCircuitBreakerConfigurationResourceManagerTest {

    private Vertx vertx;
    private ResourceStorage storage;

    private final String CONFIGURATION_URI = "/playground/server/admin/v1/circuitbreaker";

    private final String INITIAL_CONFIG_RESOURCE = ResourcesUtils.loadResource("testresource_inital_circuitbreaker_resource", true);
    private final String VALID_CONFIG_RESOURCE = ResourcesUtils.loadResource("testresource_valid_circuitbreaker_resource", true);
    private final String INVALID_CONFIG_RESOURCE = ResourcesUtils.loadResource("testresource_invalid_circuitbreaker_resource", true);

    @Before
    public void setUp(){
        vertx = Mockito.mock(Vertx.class);
        Mockito.when(vertx.eventBus()).thenReturn(Mockito.mock(EventBus.class));
        storage = new MockResourceStorage(ImmutableMap.of(CONFIGURATION_URI, INITIAL_CONFIG_RESOURCE));
    }

    @Test
    public void testValidContentShouldBeStoredInStorage(TestContext context){
        Async async = context.async();
        QueueCircuitBreakerConfigurationResourceManager manager = new QueueCircuitBreakerConfigurationResourceManager(vertx, storage, CONFIGURATION_URI);

        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        class UpdateConfigResourceWithValidDataRequest extends ConfigResourcePUTRequest {
            @Override
            public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
                bodyHandler.handle(Buffer.buffer(VALID_CONFIG_RESOURCE));
                return this;
            }

            @Override
            public HttpServerResponse response() {
                return response;
            }
        }

        storage.get(CONFIGURATION_URI, result -> {
            context.assertEquals(INITIAL_CONFIG_RESOURCE, result.toString());
            context.assertTrue(manager.handleConfigurationResource(new UpdateConfigResourceWithValidDataRequest()));
            storage.get(CONFIGURATION_URI, updatedResult -> {
                context.assertEquals(VALID_CONFIG_RESOURCE, updatedResult.toString());
                async.complete();
            });
        });
    }

    @Test
    public void testInvalidContentShouldNotBeStoredInStorage(TestContext context){
        Async async = context.async();
        QueueCircuitBreakerConfigurationResourceManager manager = new QueueCircuitBreakerConfigurationResourceManager(vertx, storage, CONFIGURATION_URI);

        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        class UpdateLoggingResourceWithValidDataRequest extends ConfigResourcePUTRequest {
            @Override
            public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
                bodyHandler.handle(Buffer.buffer(INVALID_CONFIG_RESOURCE));
                return this;
            }

            @Override
            public HttpServerResponse response() {
                return response;
            }
        }

        storage.get(CONFIGURATION_URI, result -> {
            context.assertEquals(INITIAL_CONFIG_RESOURCE, result.toString());
            context.assertTrue(manager.handleConfigurationResource(new UpdateLoggingResourceWithValidDataRequest()));
            storage.get(CONFIGURATION_URI, updatedResult -> {
                context.assertEquals(INITIAL_CONFIG_RESOURCE, updatedResult.toString(), "The configuration resource in the storage should still be equal to the initial resource");
                context.assertEquals(StatusCode.BAD_REQUEST.getStatusCode(), response.getStatusCode());
                async.complete();
            });
        });
    }

    @Test
    public void testConfigValuesShouldBeUpdatedInConfigurationResource(TestContext context){
        QueueCircuitBreakerConfigurationResourceManager manager = new QueueCircuitBreakerConfigurationResourceManager(vertx, storage, CONFIGURATION_URI);

        final DummyHttpServerResponse response = new DummyHttpServerResponse();
        class UpdateConfigResourceWithValidDataRequest extends ConfigResourcePUTRequest {
            @Override
            public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
                bodyHandler.handle(Buffer.buffer(VALID_CONFIG_RESOURCE));
                return this;
            }

            @Override
            public HttpServerResponse response() {
                return response;
            }
        }

        QueueCircuitBreakerConfigurationResource config = manager.getConfigurationResource();

        context.assertFalse(config.isCircuitCheckEnabled());
        context.assertFalse(config.isStatisticsUpdateEnabled());
        context.assertEquals(90, config.getErrorThresholdPercentage());

        context.assertTrue(manager.handleConfigurationResource(new UpdateConfigResourceWithValidDataRequest()));

        context.assertTrue(config.isCircuitCheckEnabled());
        context.assertTrue(config.isStatisticsUpdateEnabled());
        context.assertEquals(99, config.getErrorThresholdPercentage());
    }

    class ConfigResourcePUTRequest extends DummyHttpServerRequest {
        @Override public HttpMethod method() {
            return HttpMethod.PUT;
        }
        @Override public String uri() {
            return CONFIGURATION_URI;
        }
    }
}

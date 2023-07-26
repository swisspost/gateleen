package org.swisspush.gateleen.queue.queuing.circuitbreaker.configuration;

import com.google.common.collect.ImmutableMap;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.http.DummyHttpServerResponse;
import org.swisspush.gateleen.core.refresh.Refreshable;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.configuration.QueueCircuitBreakerConfigurationResource;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.configuration.QueueCircuitBreakerConfigurationResourceManager;

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
        vertx = Vertx.vertx();
        storage = new MockResourceStorage(ImmutableMap.of(CONFIGURATION_URI, INITIAL_CONFIG_RESOURCE));
    }

    @Test
    public void testRefreshablesShouldBeCalledOnConfigurationDelete(TestContext context){
        Async async = context.async(1);
        QueueCircuitBreakerConfigurationResourceManager manager = new QueueCircuitBreakerConfigurationResourceManager(vertx, storage, CONFIGURATION_URI);
        MyRefreshable refreshable = new MyRefreshable(async);
        manager.addRefreshable(refreshable);
        context.assertFalse(refreshable.isRefreshed());
        manager.handleConfigurationResource(new ConfigResourceDELETERequest());
        context.assertTrue(refreshable.isRefreshed());
        async.awaitSuccess();
    }

    @Test
    public void testRefreshablesShouldBeCalledOnConfigurationUpdateWithValidResource(TestContext context){
        Async async = context.async(1);
        QueueCircuitBreakerConfigurationResourceManager manager = new QueueCircuitBreakerConfigurationResourceManager(vertx, storage, CONFIGURATION_URI);
        MyRefreshable refreshable = new MyRefreshable(async);
        manager.addRefreshable(refreshable);
        context.assertFalse(refreshable.isRefreshed());

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

        manager.handleConfigurationResource(new UpdateConfigResourceWithValidDataRequest());
        async.awaitSuccess();
        context.assertTrue(refreshable.isRefreshed());
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
        context.assertEquals(86400000, config.getEntriesMaxAgeMS());
        context.assertEquals(100, config.getMinQueueSampleCount());
        context.assertEquals(5000, config.getMaxQueueSampleCount());
        context.assertFalse(config.isOpenToHalfOpenTaskEnabled());
        context.assertEquals(30000, config.getOpenToHalfOpenTaskInterval());
        context.assertFalse(config.isUnlockQueuesTaskEnabled());
        context.assertEquals(20000, config.getUnlockQueuesTaskInterval());
        context.assertFalse(config.isUnlockSampleQueuesTaskEnabled());
        context.assertEquals(20000, config.getUnlockSampleQueuesTaskInterval());

        context.assertTrue(manager.handleConfigurationResource(new UpdateConfigResourceWithValidDataRequest()));

        context.assertTrue(config.isCircuitCheckEnabled());
        context.assertTrue(config.isStatisticsUpdateEnabled());
        context.assertEquals(99, config.getErrorThresholdPercentage());
        context.assertEquals(43200000, config.getEntriesMaxAgeMS());
        context.assertEquals(200, config.getMinQueueSampleCount());
        context.assertEquals(2500, config.getMaxQueueSampleCount());
        context.assertTrue(config.isOpenToHalfOpenTaskEnabled());
        context.assertEquals(10000, config.getOpenToHalfOpenTaskInterval());
        context.assertTrue(config.isUnlockQueuesTaskEnabled());
        context.assertEquals(10000, config.getUnlockQueuesTaskInterval());
        context.assertTrue(config.isUnlockSampleQueuesTaskEnabled());
        context.assertEquals(10000, config.getUnlockSampleQueuesTaskInterval());
    }

    @Test
    public void testDefaultConfigValues(TestContext context){
        QueueCircuitBreakerConfigurationResourceManager manager = new QueueCircuitBreakerConfigurationResourceManager(vertx, new MockResourceStorage(), CONFIGURATION_URI);

        QueueCircuitBreakerConfigurationResource config = manager.getConfigurationResource();

        context.assertFalse(config.isCircuitCheckEnabled());
        context.assertFalse(config.isStatisticsUpdateEnabled());
        context.assertEquals(90, config.getErrorThresholdPercentage());
        context.assertEquals(86400000, config.getEntriesMaxAgeMS());
        context.assertEquals(100, config.getMinQueueSampleCount());
        context.assertEquals(5000, config.getMaxQueueSampleCount());
        context.assertFalse(config.isOpenToHalfOpenTaskEnabled());
        context.assertEquals(120000, config.getOpenToHalfOpenTaskInterval());
        context.assertFalse(config.isUnlockQueuesTaskEnabled());
        context.assertEquals(10000, config.getUnlockQueuesTaskInterval());
        context.assertFalse(config.isUnlockSampleQueuesTaskEnabled());
        context.assertEquals(120000, config.getUnlockSampleQueuesTaskInterval());
    }

    class ConfigResourceDELETERequest extends DummyHttpServerRequest {
        @Override public HttpMethod method() {
            return HttpMethod.DELETE;
        }
        @Override public String uri() {
            return CONFIGURATION_URI;
        }
    }

    class ConfigResourcePUTRequest extends DummyHttpServerRequest {
        @Override public HttpMethod method() {
            return HttpMethod.PUT;
        }
        @Override public String uri() {
            return CONFIGURATION_URI;
        }
        @Override public MultiMap headers() { return MultiMap.caseInsensitiveMultiMap(); }
    }

    static class MyRefreshable implements Refreshable {
        private boolean refreshed = false;

        private Async async;

        public MyRefreshable(Async async){
            this.async = async;
        }

        @Override
        public void refresh() {
            refreshed = true;
            async.countDown();
        }

        public boolean isRefreshed() {
            return refreshed;
        }
    }
}

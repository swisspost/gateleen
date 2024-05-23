package org.swisspush.gateleen.queue.queuing.splitter;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceObserver;
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.util.ResourcesUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.swisspush.gateleen.core.configuration.ConfigurationResourceManager.CONFIG_RESOURCE_CHANGED_ADDRESS;
import static org.swisspush.gateleen.core.exception.GateleenExceptionFactory.newGateleenWastefulExceptionFactory;

@RunWith(VertxUnitRunner.class)
public class QueueSplitterImplTest {

    private Vertx vertx;
    private MockResourceStorage storage;
    private final GateleenExceptionFactory exceptionFactory = newGateleenWastefulExceptionFactory();
    private final String configResourceUri = "/queueSplitters";
    private ConfigurationResourceManager configurationResourceManager;
    private QueueSplitterImpl queueSplitter;

    private final String CONFIG_RESOURCE_VALID_1 = ResourcesUtils.loadResource("testresource_queuesplitter_configuration_valid_1", true);
    private final String CONFIG_RESOURCE_VALID_2 = ResourcesUtils.loadResource("testresource_queuesplitter_configuration_valid_2", true);
    private final String CONFIG_RESOURCE_INVALID = ResourcesUtils.loadResource("testresource_queuesplitter_configuration_missing_postfix", true);

    @Before
    public void setUp() {
        vertx = Vertx.vertx();
        storage = new MockResourceStorage();
        configurationResourceManager = new ConfigurationResourceManager(vertx, storage, exceptionFactory);
        queueSplitter = new QueueSplitterImpl(configurationResourceManager, configResourceUri);
    }

    @Test
    public void splitWithMissingConfigResource(TestContext context) {

        // Given
        Async async = context.async();

        // When
        queueSplitter.initialize().onComplete(event -> {

            // Then
            verifySplitStaticNotExecuted(context);
            verifySplitWithHeaderNotExecuted(context);
            verifySplitWithUrlNotExecuted(context);
            async.complete();
        });
    }

    @Test
    public void splitWithValidConfigResource(TestContext context) {

        // Given
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE_VALID_1);

        // When
        queueSplitter.initialize().onComplete(event -> {

            // Then
            verifySplitStaticExecuted(context);
            verifySplitWithHeaderExecuted(context);
            verifySplitWithUrlExecuted(context);
            async.complete();
        });
    }

    @Test
    public void splitWithPartiallyInvalidConfigResource(TestContext context) {

        // Given
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE_INVALID);

        // When
        queueSplitter.initialize().onComplete(event -> {

            // Then
            verifySplitStaticNotExecuted(context);
            verifySplitWithHeaderNotExecuted(context);
            verifySplitWithUrlNotExecuted(context);
            async.complete();
        });
    }


    @Test
    public void splitWithQueueNotMatchingAnyConfiguration(TestContext context) {

        // Given
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE_VALID_1);

        // When
        queueSplitter.initialize().onComplete(event -> {

            // Then
            HttpServerRequest request = mock(HttpServerRequest.class);
            when(request.headers()).thenReturn(new HeadersMultiMap());
            context.assertEquals("another-queue", queueSplitter.convertToSubQueue("another-queue", request));
            async.complete();
        });
    }

    @Test
    public void configResourceRemovedTriggerRemoveAllExecutors(TestContext context) {

        // Given
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE_VALID_1);
        queueSplitter.initialize().onComplete(event -> {
            verifySplitStaticExecuted(context);
            verifySplitWithHeaderExecuted(context);
            verifySplitWithUrlExecuted(context);

            configurationResourceManager.registerObserver(new ConfigurationResourceObserver() {

                @Override
                public void resourceChanged(String resourceUri, Buffer resource) {
                }

                @Override
                public void resourceRemoved(String resourceUri) {

                    // Then
                    verifySplitStaticNotExecuted(context);
                    verifySplitWithHeaderNotExecuted(context);
                    verifySplitWithUrlNotExecuted(context);
                    async.complete();
                }
            }, configResourceUri);

            // When
            JsonObject object = new JsonObject();
            object.put("requestUri", configResourceUri);
            object.put("type", "remove");
            vertx.eventBus().publish(CONFIG_RESOURCE_CHANGED_ADDRESS, object);
        });
    }

    @Test
    public void configResourceChangedTriggerNewInitOfExecutors(TestContext context) {

        // Given
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE_VALID_1);
        queueSplitter.initialize().onComplete(event -> {
            verifySplitStaticExecuted(context);
            verifySplitWithHeaderExecuted(context);
            verifySplitWithUrlExecuted(context);

            configurationResourceManager.registerObserver(new ConfigurationResourceObserver() {

                private int resourceChangedCalls = 0;

                @Override
                public void resourceChanged(String resourceUri, Buffer resource) {
                    // Then
                    resourceChangedCalls++;
                    if (resourceChangedCalls == 2) {
                        verifySplitStaticExecuted(context);
                        verifySplitWithHeaderNotExecuted(context);
                        verifySplitWithUrlNotExecuted(context);
                        async.complete();
                    }
                }

                @Override
                public void resourceRemoved(String resourceUri) {
                }
            }, configResourceUri);

            // When
            storage.putMockData(configResourceUri, CONFIG_RESOURCE_VALID_2);
            JsonObject object = new JsonObject();
            object.put("requestUri", configResourceUri);
            object.put("type", "change");
            vertx.eventBus().publish(CONFIG_RESOURCE_CHANGED_ADDRESS, object);
        });
    }

    private void verifySplitStaticExecuted(TestContext context) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        context.assertEquals("my-queue-1-A", queueSplitter.convertToSubQueue("my-queue-1", request));
        context.assertEquals("my-queue-1-B", queueSplitter.convertToSubQueue("my-queue-1", request));
        context.assertEquals("my-queue-1-C", queueSplitter.convertToSubQueue("my-queue-1", request));
    }

    private void verifySplitStaticNotExecuted(TestContext context) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        context.assertEquals("my-queue-1", queueSplitter.convertToSubQueue("my-queue-1", request));
    }

    private void verifySplitWithHeaderExecuted(TestContext context) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.headers()).thenReturn(new HeadersMultiMap().add("x-rp-deviceid", "A1B2C3D4E5F6"));
        context.assertEquals("my-queue-2+A1B2C3D4E5F6", queueSplitter.convertToSubQueue("my-queue-2", request));
    }

    private void verifySplitWithHeaderNotExecuted(TestContext context) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.headers()).thenReturn(new HeadersMultiMap().add("x-rp-deviceid", "A1B2C3D4E5F6"));
        context.assertEquals("my-queue-2", queueSplitter.convertToSubQueue("my-queue-2", request));
    }

    private void verifySplitWithUrlExecuted(TestContext context) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.headers()).thenReturn(new HeadersMultiMap());
        when(request.uri()).thenReturn("/path1/path2/path3/path4/");
        context.assertEquals("my-queue-a_path2", queueSplitter.convertToSubQueue("my-queue-a", request));
    }

    private void verifySplitWithUrlNotExecuted(TestContext context) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.headers()).thenReturn(new HeadersMultiMap());
        when(request.uri()).thenReturn("/path1/path2/path3/path4/");
        context.assertEquals("my-queue-a", queueSplitter.convertToSubQueue("my-queue-a", request));
    }
}

package org.swisspush.gateleen.queue.queuing.splitter;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceObserver;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.util.ResourcesUtils;

import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.*;
import static org.swisspush.gateleen.core.configuration.ConfigurationResourceManager.CONFIG_RESOURCE_CHANGED_ADDRESS;

@RunWith(VertxUnitRunner.class)
public class QueueSplitterImplTest {

    private Vertx vertx;
    private MockResourceStorage storage;
    private final String configResourceUri = "/queueSplitters";
    private ConfigurationResourceManager configurationResourceManager;
    private QueueSplitterImpl queueSplitter;

    private final String CONFIG_RESOURCE_VALID = ResourcesUtils.loadResource("testresource_queuesplitter_configuration_valid", true);
    private final String CONFIG_RESOURCE_INVALID = ResourcesUtils.loadResource("testresource_queuesplitter_configuration_invalid", true);

    @Before
    public void setUp() {
        vertx = Vertx.vertx();
        storage = new MockResourceStorage();
        configurationResourceManager = new ConfigurationResourceManager(vertx, storage);
        queueSplitter = new QueueSplitterImpl(configurationResourceManager, configResourceUri);
    }

    @Test
    public void splitWithMissingConfigResource(TestContext context) {
        Async async = context.async();
        context.assertFalse(queueSplitter.isInitialized());
        queueSplitter.initialize().onComplete(event -> {
            context.assertFalse(queueSplitter.isInitialized());
            verifySplitStaticNotExecuted(context);
            verifySplitWithHeaderNotExecuted(context);
            verifySplitWithUrlNotExecuted(context);
            async.complete();
        });
    }

    @Test
    public void splitWithValidConfigResource(TestContext context) {
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE_VALID);
        context.assertFalse(queueSplitter.isInitialized());
        queueSplitter.initialize().onComplete(event -> {
            context.assertTrue(queueSplitter.isInitialized());
            verifySplitStaticExecuted(context);
            verifySplitWithHeaderExecuted(context);
            verifySplitWithUrlExecuted(context);
            async.complete();
        });
    }

    @Test
    public void splitWithPartiallyInvalidConfigResource(TestContext context) {
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE_INVALID);
        context.assertFalse(queueSplitter.isInitialized());
        queueSplitter.initialize().onComplete(event -> {
            context.assertTrue(queueSplitter.isInitialized());
            verifySplitStaticExecuted(context);
            verifySplitWithHeaderNotExecuted(context);
            verifySplitWithUrlNotExecuted(context);
            async.complete();
        });
    }


    @Test
    public void splitWithQueueNotMatchingAnyConfiguration(TestContext context) {
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE_VALID);
        context.assertFalse(queueSplitter.isInitialized());
        queueSplitter.initialize().onComplete(event -> {
            HttpServerRequest request = mock(HttpServerRequest.class);
            when(request.headers()).thenReturn(new HeadersMultiMap());
            context.assertEquals("another-queue", queueSplitter.convertToSubQueue("another-queue", request));
            async.complete();
        });
    }

    @Test
    public void configResourceRemovedTriggerRemoveAllExecutorsOld(TestContext context) {
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE_VALID);
        queueSplitter.initialize().onComplete(event -> {
            context.assertTrue(queueSplitter.isInitialized());
            verifySplitStaticExecuted(context);
            verifySplitWithHeaderExecuted(context);
            verifySplitWithUrlExecuted(context);

            configurationResourceManager.registerObserver(new ConfigurationResourceObserver() {

                @Override
                public void resourceChanged(String resourceUri, Buffer resource) {
                }

                @Override
                public void resourceRemoved(String resourceUri) {
                    context.assertFalse(queueSplitter.isInitialized());
                    verifySplitStaticNotExecuted(context);
                    verifySplitWithHeaderNotExecuted(context);
                    verifySplitWithUrlNotExecuted(context);
                    async.complete();
                }
            }, configResourceUri);

            JsonObject object = new JsonObject();
            object.put("requestUri", configResourceUri);
            object.put("type", "remove");
            vertx.eventBus().publish(CONFIG_RESOURCE_CHANGED_ADDRESS, object);
        });
    }

    @Test
    public void configResourceRemovedTriggerRemoveAllExecutors(TestContext context) {
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE_VALID);
        queueSplitter.initialize().onComplete(event -> {
            context.assertTrue(queueSplitter.isInitialized());
            verifySplitStaticExecuted(context);
            verifySplitWithHeaderExecuted(context);
            verifySplitWithUrlExecuted(context);

            vertx.eventBus().consumer(CONFIG_RESOURCE_CHANGED_ADDRESS, (Handler<Message<JsonObject>>) message -> {
                context.assertEquals("remove", message.body().getString("type"));
                context.assertFalse(queueSplitter.isInitialized());
                verifySplitStaticNotExecuted(context);
                verifySplitWithHeaderNotExecuted(context);
                verifySplitWithUrlNotExecuted(context);
                async.complete();
            });
            JsonObject object = new JsonObject();
            object.put("requestUri", configResourceUri);
            object.put("type", "remove");
            vertx.eventBus().publish(CONFIG_RESOURCE_CHANGED_ADDRESS, object);
        });
    }

    @Test
    public void configResourceChangedTriggerNewInitOfExecutorsOld(TestContext context) {
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE_VALID);

        queueSplitter.initialize().onComplete(event -> {
            context.assertTrue(queueSplitter.isInitialized());
            verifySplitStaticExecuted(context);
            verifySplitWithHeaderExecuted(context);
            verifySplitWithUrlExecuted(context);

            configurationResourceManager.registerObserver(new ConfigurationResourceObserver() {

                private int resourceChangedCalls = 0;

                @Override
                public void resourceChanged(String resourceUri, Buffer resource) {
                    resourceChangedCalls++;
                    if (resourceChangedCalls == 2) {
                        context.assertTrue(queueSplitter.isInitialized());
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

            storage.putMockData(configResourceUri, CONFIG_RESOURCE_INVALID);
            JsonObject object = new JsonObject();
            object.put("requestUri", configResourceUri);
            object.put("type", "change");
            vertx.eventBus().publish(CONFIG_RESOURCE_CHANGED_ADDRESS, object);
        });
    }

    @Test
    @Ignore("to review")
    public void configResourceChangedTriggerNewInitOfExecutors1(TestContext context) {
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE_VALID);

        queueSplitter.initialize().onComplete(event -> {

            context.assertTrue(queueSplitter.isInitialized());
            verifySplitStaticExecuted(context);
            verifySplitWithHeaderExecuted(context);
            verifySplitWithUrlExecuted(context);

            storage.putMockData(configResourceUri, CONFIG_RESOURCE_INVALID);
            JsonObject object = new JsonObject();
            object.put("requestUri", configResourceUri);
            object.put("type", "change");
            vertx.eventBus().publish(CONFIG_RESOURCE_CHANGED_ADDRESS, object);

            await().atMost(10, SECONDS).until( () -> {
                HttpServerRequest request = mock(HttpServerRequest.class);
                when(request.headers()).thenReturn(new HeadersMultiMap().add("x-rp-deviceid", "A1B2C3D4E5F6"));
                String value = queueSplitter.convertToSubQueue("my-queue-2", request);
                System.out.println("await called");
                return "my-queue-2".equals(value);
            }, equalTo(Boolean.TRUE));

            context.assertTrue(queueSplitter.isInitialized());
//            verifySplitStaticExecuted(context);
//            verifySplitWithHeaderNotExecuted(context);
//            verifySplitWithUrlNotExecuted(context);
//             async.complete();
        });
    }

    @Test
    @Ignore("to review")
    public void configResourceChangedTriggerNewInitOfExecutors2(TestContext context) {
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE_VALID);

        AtomicInteger resourceChangedCalls = new AtomicInteger(0);
        vertx.eventBus().consumer(CONFIG_RESOURCE_CHANGED_ADDRESS, (Handler<Message<JsonObject>>) message -> {

            int counter = resourceChangedCalls.incrementAndGet();
            if (counter == 1) {
                context.assertEquals("change", message.body().getString("type"));
                context.assertFalse(queueSplitter.isInitialized());
                verifySplitStaticNotExecuted(context);
                verifySplitWithHeaderNotExecuted(context);
                verifySplitWithUrlNotExecuted(context);
                async.complete();
            } else if (counter == 2) {
                context.assertEquals("change", message.body().getString("type"));
                context.assertTrue(queueSplitter.isInitialized());
                verifySplitStaticExecuted(context);
                verifySplitWithHeaderExecuted(context);
                verifySplitWithUrlExecuted(context);
                async.complete();
            }
        });

        storage.putMockData(configResourceUri, CONFIG_RESOURCE_INVALID);
        JsonObject object = new JsonObject();
        object.put("requestUri", configResourceUri);
        object.put("type", "change");
        vertx.eventBus().publish(CONFIG_RESOURCE_CHANGED_ADDRESS, object);
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

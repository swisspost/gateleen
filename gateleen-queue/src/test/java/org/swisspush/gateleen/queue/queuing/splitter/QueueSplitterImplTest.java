package org.swisspush.gateleen.queue.queuing.splitter;

import io.vertx.core.Vertx;
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
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.queue.queuing.splitter.executors.QueueSplitExecutor;
import org.swisspush.gateleen.queue.queuing.splitter.executors.QueueSplitExecutorFromList;
import org.swisspush.gateleen.queue.queuing.splitter.executors.QueueSplitExecutorFromRequest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
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
    public void initWithMissingConfigResource(TestContext context) {
        Async async = context.async();
        context.assertFalse(queueSplitter.isInitialized());
        queueSplitter.initialize().onComplete(event -> {
            context.assertFalse(queueSplitter.isInitialized());
            context.assertTrue(queueSplitter.getQueueSplitExecutors().isEmpty());
            async.complete();
        });
    }

    @Test
    public void initWithExistingConfigResource(TestContext context) {
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE_VALID);
        context.assertFalse(queueSplitter.isInitialized());
        queueSplitter.initialize().onComplete(event -> {
            context.assertTrue(queueSplitter.isInitialized());
            context.assertEquals(3, queueSplitter.getQueueSplitExecutors().size());

            QueueSplitExecutor executor_1 = queueSplitter.getQueueSplitExecutors().get(0);
            context.assertTrue(executor_1 instanceof QueueSplitExecutorFromList);

            QueueSplitExecutor executor_2 = queueSplitter.getQueueSplitExecutors().get(1);
            context.assertTrue(executor_2 instanceof QueueSplitExecutorFromRequest);

            QueueSplitExecutor executor_3 = queueSplitter.getQueueSplitExecutors().get(2);
            context.assertTrue(executor_3 instanceof QueueSplitExecutorFromRequest);

            async.complete();
        });
    }

    @Test
    public void initWithExistingInvalidConfigResource(TestContext context) {
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE_INVALID);
        context.assertFalse(queueSplitter.isInitialized());
        queueSplitter.initialize().onComplete(event -> {
            context.assertTrue(queueSplitter.isInitialized());
            context.assertEquals(1, queueSplitter.getQueueSplitExecutors().size());

            QueueSplitExecutor executor_1 = queueSplitter.getQueueSplitExecutors().get(0);
            context.assertTrue(executor_1 instanceof QueueSplitExecutorFromList);

            async.complete();
        });
    }

    @Test
    @Ignore("verify with timeout and await don't work, review with Marc")
    public void configResourceRemovedTriggerRemoveAllExecutors(TestContext context) {
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE_VALID);
        context.assertFalse(queueSplitter.isInitialized());
        queueSplitter.initialize().onComplete(event -> {
            context.assertTrue(queueSplitter.isInitialized());
            context.assertEquals(3, queueSplitter.getQueueSplitExecutors().size());
            JsonObject object = new JsonObject();
            object.put("requestUri", configResourceUri);
            object.put("type", "remove");
            vertx.eventBus().publish(CONFIG_RESOURCE_CHANGED_ADDRESS, object);
            // try { Thread.sleep(2000);} catch (InterruptedException e) {}
            // verify(storage, timeout(100).times(1)).delete(eq(CONFIG_RESOURCE_CHANGED_ADDRESS), any());
            // await().atMost(TWO_SECONDS).until(() -> queueSplitter.getQueueSplitExecutors(), equalTo(0));
            context.assertEquals(0, queueSplitter.getQueueSplitExecutors().size());
            async.complete();
        });
    }

    @Test
    @Ignore("verify with timeout and await don't work, review with Marc")
    public void configResourceChangedTriggerNewInitOfExecutors(TestContext context) {
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE_VALID);
        context.assertFalse(queueSplitter.isInitialized());
        queueSplitter.initialize().onComplete(event -> {
            context.assertTrue(queueSplitter.isInitialized());
            context.assertEquals(3, queueSplitter.getQueueSplitExecutors().size());

            JsonObject object = new JsonObject();
            object.put("requestUri", configResourceUri);
            object.put("type", "change");
            vertx.eventBus().publish(CONFIG_RESOURCE_CHANGED_ADDRESS, object);
            // try { Thread.sleep(2000);} catch (InterruptedException e) {}
            // verify(storage, timeout(100).times(1)).delete(eq(CONFIG_RESOURCE_CHANGED_ADDRESS), any());
            // await().atMost(TWO_SECONDS).until(() -> queueSplitter.getQueueSplitExecutors(), equalTo(0));
            context.assertEquals(0, queueSplitter.getQueueSplitExecutors().size());
            async.complete();
        });
    }

    @Test
    public void testConvertToSubQueueWithPostfixFromStatic(TestContext context) {
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE_VALID);
        context.assertFalse(queueSplitter.isInitialized());
        queueSplitter.initialize().onComplete(event -> {
            HttpServerRequest request = mock(HttpServerRequest.class);
            when(request.headers()).thenReturn(new HeadersMultiMap());
            context.assertEquals("my-queue-1-A", queueSplitter.convertToSubQueue("my-queue-1", request));
            context.assertEquals("my-queue-1-B", queueSplitter.convertToSubQueue("my-queue-1", request));
            context.assertEquals("my-queue-1-C", queueSplitter.convertToSubQueue("my-queue-1", request));
            async.complete();
        });
    }

    @Test
    public void testConvertToSubQueueWithPostfixFromHeader(TestContext context) {
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE_VALID);
        context.assertFalse(queueSplitter.isInitialized());
        queueSplitter.initialize().onComplete(event -> {
            HttpServerRequest request = mock(HttpServerRequest.class);
            when(request.headers()).thenReturn(new HeadersMultiMap().add("x-rp-deviceid", "A1B2C3D4E5F6"));
            context.assertEquals("my-queue-2+A1B2C3D4E5F6", queueSplitter.convertToSubQueue("my-queue-2", request));
            async.complete();
        });
    }
    @Test
    public void testConvertToSubQueueWithPostfixFromUrl(TestContext context) {
        Async async = context.async();
        storage.putMockData(configResourceUri, CONFIG_RESOURCE_VALID);
        context.assertFalse(queueSplitter.isInitialized());
        queueSplitter.initialize().onComplete(event -> {
            HttpServerRequest request = mock(HttpServerRequest.class);
            when(request.headers()).thenReturn(new HeadersMultiMap());
            when(request.uri()).thenReturn("/path1/path2/path3/path4/");
            context.assertEquals("my-queue-a_path2", queueSplitter.convertToSubQueue("my-queue-a", request));
            async.complete();
        });
    }

    @Test
    public void testConvertToSubQueueWithNoPostfixConfigured(TestContext context) {
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
}

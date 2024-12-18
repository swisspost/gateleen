package org.swisspush.gateleen.queue.queuing.circuitbreaker.impl;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.redis.client.impl.types.MultiType;
import io.vertx.redis.client.impl.types.SimpleStringType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.lock.Lock;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.QueueCircuitBreakerStorage;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.configuration.QueueCircuitBreakerConfigurationResource;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.configuration.QueueCircuitBreakerConfigurationResourceManager;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.*;
import org.swisspush.gateleen.routing.RuleProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.swisspush.gateleen.core.exception.GateleenExceptionFactory.newGateleenWastefulExceptionFactory;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueResponseType.SUCCESS;

/**
 * Tests for the {@link QueueCircuitBreakerImpl} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class QueueCircuitBreakerImplTest {

    private Vertx vertx;
    private Lock lock;
    private ResourceStorage storage;
    private RuleProvider ruleProvider;
    private QueueCircuitBreakerStorage queueCircuitBreakerStorage;
    private Map<String, Object> props = new HashMap<>();
    private QueueCircuitBreakerImpl queueCircuitBreaker;
    private QueueCircuitBreakerRulePatternToCircuitMapping ruleToCircuitMapping;
    private QueueCircuitBreakerConfigurationResourceManager configResourceManager;

    @org.junit.Rule
    public Timeout rule = Timeout.seconds(5);

    @Before
    public void setUp() {
        vertx = Vertx.vertx();

        lock = Mockito.mock(Lock.class);
        Mockito.when(lock.acquireLock(anyString(), anyString(), anyLong())).thenReturn(Future.succeededFuture(Boolean.TRUE));
        Mockito.when(lock.releaseLock(anyString(), anyString())).thenReturn(Future.succeededFuture(Boolean.TRUE));

        storage = Mockito.mock(ResourceStorage.class);

        queueCircuitBreakerStorage = Mockito.mock(QueueCircuitBreakerStorage.class);
        Mockito.when(queueCircuitBreakerStorage.setOpenCircuitsToHalfOpen()).thenReturn(Future.succeededFuture(0L));
        Mockito.when(queueCircuitBreakerStorage.popQueueToUnlock()).thenReturn(Future.succeededFuture("SomeQueue"));
        Mockito.when(queueCircuitBreakerStorage.unlockSampleQueues()).thenReturn(Future.succeededFuture(MultiType.EMPTY_MULTI));

        ruleProvider = new RuleProvider(vertx, "/path/to/routing/rules", storage, props);
        ruleToCircuitMapping = Mockito.mock(QueueCircuitBreakerRulePatternToCircuitMapping.class);

        configResourceManager = Mockito.mock(QueueCircuitBreakerConfigurationResourceManager.class);
        QueueCircuitBreakerConfigurationResource config = new QueueCircuitBreakerConfigurationResource();
        config.setOpenToHalfOpenTaskEnabled(true);
        config.setOpenToHalfOpenTaskInterval(1000);
        config.setUnlockQueuesTaskEnabled(true);
        config.setUnlockQueuesTaskInterval(1000);
        config.setUnlockSampleQueuesTaskEnabled(true);
        config.setUnlockSampleQueuesTaskInterval(1000);
        Mockito.when(configResourceManager.getConfigurationResource()).thenReturn(config);

        var exceptionFactory = newGateleenWastefulExceptionFactory();
        Handler<HttpServerRequest> queueCircuitBreakerHttpRequestHandler = Mockito.mock(Handler.class);
        queueCircuitBreaker = Mockito.spy(new QueueCircuitBreakerImpl(vertx, lock, Address.redisquesAddress(), queueCircuitBreakerStorage, ruleProvider,
                exceptionFactory, ruleToCircuitMapping, configResourceManager, queueCircuitBreakerHttpRequestHandler, 9999));
    }

    @After
    public void cleanUp() {
        vertx.close();
        Mockito.reset(lock);
        Mockito.reset(storage);
        Mockito.reset(queueCircuitBreakerStorage);
        Mockito.reset(ruleToCircuitMapping);
        Mockito.reset(configResourceManager);
        Mockito.reset(queueCircuitBreaker);
    }

    @Test
    public void testLockingForPeriodicTimersSuccess(TestContext context) {
        Async async = context.async();

        Mockito.when(queueCircuitBreakerStorage.setOpenCircuitsToHalfOpen()).thenReturn(Future.succeededFuture(0L));
        Mockito.when(queueCircuitBreakerStorage.popQueueToUnlock()).thenReturn(Future.succeededFuture("SomeQueue"));
        Mockito.when(queueCircuitBreakerStorage.unlockSampleQueues()).thenReturn(Future.succeededFuture(MultiType.EMPTY_MULTI));

        vertx.eventBus().consumer(Address.redisquesAddress(), (Message<JsonObject> event) -> {
            context.assertEquals("deleteLock", event.body().getString("operation"));
            context.assertEquals("SomeQueue", event.body().getJsonObject("payload").getString("queuename"));
            event.reply(new JsonObject().put("status", "ok"));

            Mockito.verify(queueCircuitBreakerStorage, after(1200).atMost(1)).setOpenCircuitsToHalfOpen();
            Mockito.verify(queueCircuitBreakerStorage, after(1200).atMost(1)).popQueueToUnlock();
            Mockito.verify(queueCircuitBreakerStorage, after(1200).atMost(1)).unlockSampleQueues();
            async.complete();
        });

        ArgumentCaptor<String> lockArguments = ArgumentCaptor.forClass(String.class);

        Mockito.verify(lock, timeout(1100).times(3)).acquireLock(lockArguments.capture(), anyString(), anyLong());
        Mockito.verify(lock, after(1100).never()).releaseLock(anyString(), anyString());

        List<String> lockValues = lockArguments.getAllValues();
        context.assertTrue(lockValues.contains(QueueCircuitBreakerImpl.OPEN_TO_HALF_OPEN_TASK_LOCK));
        context.assertTrue(lockValues.contains(QueueCircuitBreakerImpl.UNLOCK_QUEUES_TASK_LOCK));
        context.assertTrue(lockValues.contains(QueueCircuitBreakerImpl.UNLOCK_SAMPLE_QUEUES_TASK_LOCK));

        async.awaitSuccess();
    }

    @Test
    public void testLockingForPeriodicTimersFail(TestContext context) {
        Async async = context.async();

        Mockito.when(queueCircuitBreakerStorage.setOpenCircuitsToHalfOpen()).thenReturn(Future.failedFuture("setOpenCircuitsToHalfOpen::booom"));
        Mockito.when(queueCircuitBreakerStorage.popQueueToUnlock()).thenReturn(Future.failedFuture("popQueueToUnlock::booom"));
        Mockito.when(queueCircuitBreakerStorage.unlockSampleQueues()).thenReturn(Future.failedFuture("unlockSampleQueues::booom"));

        ArgumentCaptor<String> lockArguments = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> releaseArguments = ArgumentCaptor.forClass(String.class);

        Mockito.verify(lock, timeout(1100).times(3)).acquireLock(lockArguments.capture(), anyString(), anyLong());
        Mockito.verify(lock, timeout(1100).times(3)).releaseLock(releaseArguments.capture(), anyString());

        List<String> lockValues = lockArguments.getAllValues();
        context.assertTrue(lockValues.contains(QueueCircuitBreakerImpl.OPEN_TO_HALF_OPEN_TASK_LOCK));
        context.assertTrue(lockValues.contains(QueueCircuitBreakerImpl.UNLOCK_QUEUES_TASK_LOCK));
        context.assertTrue(lockValues.contains(QueueCircuitBreakerImpl.UNLOCK_SAMPLE_QUEUES_TASK_LOCK));

        List<String> releaseValues = releaseArguments.getAllValues();
        context.assertTrue(releaseValues.contains(QueueCircuitBreakerImpl.OPEN_TO_HALF_OPEN_TASK_LOCK));
        context.assertTrue(releaseValues.contains(QueueCircuitBreakerImpl.UNLOCK_QUEUES_TASK_LOCK));
        context.assertTrue(releaseValues.contains(QueueCircuitBreakerImpl.UNLOCK_SAMPLE_QUEUES_TASK_LOCK));

        Mockito.verify(queueCircuitBreakerStorage, timeout(1200).times(1)).setOpenCircuitsToHalfOpen();
        Mockito.verify(queueCircuitBreakerStorage, timeout(1200).times(1)).popQueueToUnlock();
        Mockito.verify(queueCircuitBreakerStorage, timeout(1200).times(1)).unlockSampleQueues();

        async.complete();
        async.awaitSuccess();
    }

    @Test
    public void testHandleQueuedRequest(TestContext context) {
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash", "my-metric-1"));

        Mockito.when(queueCircuitBreakerStorage.getQueueCircuitState(any(PatternAndCircuitHash.class)))
                .thenReturn(Future.succeededFuture(QueueCircuitState.CLOSED));

        Mockito.when(queueCircuitBreakerStorage.lockQueue(anyString(), any(PatternAndCircuitHash.class)))
                .thenReturn(Future.succeededFuture());

        queueCircuitBreaker.handleQueuedRequest("someQueue", req).onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(QueueCircuitState.CLOSED, event.result());

            Mockito.when(queueCircuitBreakerStorage.getQueueCircuitState(any(PatternAndCircuitHash.class)))
                    .thenReturn(Future.succeededFuture(QueueCircuitState.OPEN));

            queueCircuitBreaker.handleQueuedRequest("someQueue", req).onComplete(event1 -> {
                context.assertTrue(event1.succeeded());
                context.assertEquals(QueueCircuitState.OPEN, event1.result());
                async.complete();
            });
        });
        async.awaitSuccess();
    }

    @Test
    public void testHandleQueuedRequestCallsLockQueueWhenCircuitIsOpen(TestContext context) {
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash", "my-metric-1"));

        Mockito.when(queueCircuitBreakerStorage.getQueueCircuitState(any(PatternAndCircuitHash.class)))
                .thenReturn(Future.succeededFuture(QueueCircuitState.OPEN));

        Mockito.when(queueCircuitBreakerStorage.lockQueue(anyString(), any(PatternAndCircuitHash.class)))
                .thenReturn(Future.succeededFuture());

        queueCircuitBreaker.handleQueuedRequest("someQueue", req).onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(QueueCircuitState.OPEN, event.result());
            verify(queueCircuitBreaker, times(1)).lockQueue("someQueue", req);
            async.complete();
        });
        async.awaitSuccess();
    }

    @Test
    public void testHandleQueuedRequestDoesNotCallLockQueueWhenCircuitIsNotOpen(TestContext context) {
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash", "my-metric-1"));

        Mockito.when(queueCircuitBreakerStorage.getQueueCircuitState(any(PatternAndCircuitHash.class)))
                .thenReturn(Future.succeededFuture(QueueCircuitState.CLOSED));

        queueCircuitBreaker.handleQueuedRequest("someQueue", req).onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(QueueCircuitState.CLOSED, event.result());
            verify(queueCircuitBreaker, never()).lockQueue(anyString(), any(HttpRequest.class));

            Mockito.when(queueCircuitBreakerStorage.getQueueCircuitState(any(PatternAndCircuitHash.class)))
                    .thenReturn(Future.succeededFuture(QueueCircuitState.HALF_OPEN));

            queueCircuitBreaker.handleQueuedRequest("someQueue", req).onComplete(event1 -> {
                context.assertTrue(event1.succeeded());
                context.assertEquals(QueueCircuitState.HALF_OPEN, event1.result());
                verify(queueCircuitBreaker, never()).lockQueue(anyString(), any(HttpRequest.class));
                async.complete();
            });
        });
        async.awaitSuccess();
    }

    @Test
    public void testHandleQueuedRequestNoCircuitMapping(TestContext context) {
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString())).thenReturn(null);

        queueCircuitBreaker.handleQueuedRequest("someQueue", req).onComplete(event -> {
            context.assertTrue(event.failed());
            context.assertNotNull(event.cause());
            context.assertTrue(event.cause().getMessage().contains("no rule to circuit mapping found for queue"));
            async.complete();
        });
        async.awaitSuccess();
    }

    @Test
    public void testUpdateStatistics(TestContext context) {
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash", "my-metric-1"));

        Mockito.when(queueCircuitBreakerStorage.updateStatistics(any(PatternAndCircuitHash.class),
                anyString(), anyLong(), anyInt(), anyLong(), anyLong(), anyLong(), any(QueueResponseType.class)))
                .thenReturn(Future.succeededFuture(UpdateStatisticsResult.OK));

        queueCircuitBreaker.updateStatistics("someQueue", req, SUCCESS).onComplete(event -> {
            context.assertTrue(event.succeeded());
            verify(queueCircuitBreaker, never()).lockQueue(anyString(), any(HttpRequest.class));
            async.complete();
        });
        async.awaitSuccess();
    }

    @Test
    public void testUpdateStatisticsNoCircuitMapping(TestContext context) {
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString())).thenReturn(null);

        queueCircuitBreaker.updateStatistics("someQueueName", req, SUCCESS).onComplete(event -> {
            context.assertTrue(event.failed());
            context.assertNotNull(event.cause());
            context.assertTrue(event.cause().getMessage().contains("no rule to circuit mapping found for queue"));
            verify(queueCircuitBreaker, never()).lockQueue(anyString(), any(HttpRequest.class));
            async.complete();
        });
        async.awaitSuccess();
    }

    @Test
    public void testUpdateStatisticsTriggersQueueLock(TestContext context) {
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash", "my-metric-1"));

        Mockito.when(queueCircuitBreakerStorage.updateStatistics(any(PatternAndCircuitHash.class),
                anyString(), anyLong(), anyInt(), anyLong(), anyLong(), anyLong(), any(QueueResponseType.class)))
                .thenReturn(Future.succeededFuture(UpdateStatisticsResult.OPENED));

        Mockito.when(queueCircuitBreakerStorage.lockQueue(anyString(), any(PatternAndCircuitHash.class)))
                .thenReturn(Future.succeededFuture());

        queueCircuitBreaker.updateStatistics("someQueue", req, SUCCESS).onComplete(event -> {
            context.assertTrue(event.succeeded());
            verify(queueCircuitBreaker, times(1)).lockQueue("someQueue", req);
            async.complete();
        });
        async.awaitSuccess();
    }

    @Test
    public void testQueueLock(TestContext context) {
        Async async = context.async(2);
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash", "my-metric-1"));

        Mockito.when(queueCircuitBreakerStorage.lockQueue(anyString(), any(PatternAndCircuitHash.class)))
                .thenReturn(Future.succeededFuture());

        vertx.eventBus().consumer(Address.redisquesAddress(), (Message<JsonObject> event) -> {
            context.assertEquals("putLock", event.body().getString("operation"));
            event.reply(new JsonObject().put("status", "ok"));
            async.countDown();
        });

        queueCircuitBreaker.lockQueue("someQueue", req).onComplete(event -> {
            context.assertTrue(event.succeeded());
            verify(queueCircuitBreakerStorage, times(1)).lockQueue(anyString(), any(PatternAndCircuitHash.class));
            async.countDown();
        });

        async.awaitSuccess();
    }

    @Test
    public void testQueueLockFailingRedisques(TestContext context) {
        Async async = context.async(2);
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash", "my-metric-1"));

        Mockito.when(queueCircuitBreakerStorage.lockQueue(anyString(), any(PatternAndCircuitHash.class)))
                .thenReturn(Future.succeededFuture());

        vertx.eventBus().consumer(Address.redisquesAddress(), (Message<JsonObject> event) -> {
            context.assertEquals("putLock", event.body().getString("operation"));
            event.reply(new JsonObject().put("status", "error"));
            async.countDown();
        });

        queueCircuitBreaker.lockQueue("someQueue", req).onComplete(event -> {
            context.assertTrue(event.failed());
            context.assertTrue(event.cause().getMessage().contains("failed to lock queue 'someQueue'"));
            verify(queueCircuitBreakerStorage, times(1)).lockQueue(anyString(), any(PatternAndCircuitHash.class));
            async.countDown();
        });

        async.awaitSuccess();
    }

    @Test
    public void testQueueLockFailingStorage(TestContext context) {
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash", "my-metric-1"));

        Mockito.when(queueCircuitBreakerStorage.lockQueue(anyString(), any(PatternAndCircuitHash.class)))
                .thenReturn(Future.failedFuture("queue could not be locked"));

        vertx.eventBus().consumer(Address.redisquesAddress(), (Message<JsonObject> event) -> context.fail("Redisques should not have been called when the storage failed"));

        queueCircuitBreaker.lockQueue("someQueue", req).onComplete(event -> {
            context.assertTrue(event.failed());
            context.assertTrue(event.cause().getMessage().contains("queue could not be locked"));
            verify(queueCircuitBreakerStorage, times(1)).lockQueue(anyString(), any(PatternAndCircuitHash.class));
            async.complete();
        });
        async.awaitSuccess();
    }

    @Test
    public void testUnlockNextQueue(TestContext context) {
        Async async = context.async(2);

        Mockito.when(queueCircuitBreakerStorage.popQueueToUnlock())
                .thenReturn(Future.succeededFuture("queue_1"));

        vertx.eventBus().consumer(Address.redisquesAddress(), (Message<JsonObject> event) -> {
            context.assertEquals("deleteLock", event.body().getString("operation"));
            event.reply(new JsonObject().put("status", "ok"));
            async.countDown();
        });

        queueCircuitBreaker.unlockNextQueue().onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals("queue_1", event.result());
            verify(queueCircuitBreakerStorage, times(1)).popQueueToUnlock();
            async.countDown();
        });
        async.awaitSuccess();
    }

    @Test
    public void testUnlockNextQueueFailingRedisques(TestContext context) {
        Async async = context.async(2);

        Mockito.when(queueCircuitBreakerStorage.popQueueToUnlock())
                .thenReturn(Future.succeededFuture("queue_1"));

        vertx.eventBus().consumer(Address.redisquesAddress(), (Message<JsonObject> event) -> {
            context.assertEquals("deleteLock", event.body().getString("operation"));
            event.reply(new JsonObject().put("status", "error"));
            async.countDown();
        });

        queueCircuitBreaker.unlockNextQueue().onComplete(event -> {
            context.assertTrue(event.failed());
            context.assertTrue(event.cause().getMessage().equalsIgnoreCase("queue_1"));
            verify(queueCircuitBreakerStorage, times(1)).popQueueToUnlock();
            async.countDown();
        });
        async.awaitSuccess();
    }

    @Test
    public void testUnlockNextQueueFailingStorage(TestContext context) {
        Async async = context.async();

        Mockito.when(queueCircuitBreakerStorage.popQueueToUnlock())
                .thenReturn(Future.failedFuture("unable to pop queueToUnlock from list"));

        vertx.eventBus().consumer(Address.redisquesAddress(), (Message<JsonObject> event) ->
                context.fail("Redisques should not have been called when the storage failed"));

        queueCircuitBreaker.unlockNextQueue().onComplete(event -> {
            context.assertTrue(event.failed());
            context.assertTrue(event.cause().getMessage().contains("unable to pop queueToUnlock from list"));
            verify(queueCircuitBreakerStorage, times(1)).popQueueToUnlock();
            async.complete();
        });
        async.awaitSuccess();
    }

    @Test
    public void testUnlockSampleQueues(TestContext context) {
        Async async = context.async(4);
        MultiType responses = MultiType.create(3, false);
        responses.add(SimpleStringType.create("q1"));
        responses.add(SimpleStringType.create("q2"));
        responses.add(SimpleStringType.create("q3"));
        Mockito.when(queueCircuitBreakerStorage.unlockSampleQueues())
                .thenReturn(Future.succeededFuture(responses));

        vertx.eventBus().consumer(Address.redisquesAddress(), (Message<JsonObject> event) -> {
            context.assertEquals("deleteLock", event.body().getString("operation"));
            event.reply(new JsonObject().put("status", "ok"));
            async.countDown();
        });

        queueCircuitBreaker.unlockSampleQueues().onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(3L, event.result());
            async.countDown();
        });

        async.awaitSuccess();
    }

    @Test
    public void testUnlockSampleQueuesFailingUnlock(TestContext context) {
        Async async = context.async(4);
        MultiType responses = MultiType.create(3, false);
        responses.add(SimpleStringType.create("q1"));
        responses.add(SimpleStringType.create("q2"));
        responses.add(SimpleStringType.create("q3"));
        Mockito.when(queueCircuitBreakerStorage.unlockSampleQueues())
                .thenReturn(Future.succeededFuture(responses));

        vertx.eventBus().consumer(Address.redisquesAddress(), (Message<JsonObject> event) -> {
            context.assertEquals("deleteLock", event.body().getString("operation"));
            if (event.body().getJsonObject("payload").getString("queuename").equalsIgnoreCase("q2")) {
                event.reply(new JsonObject().put("status", "error"));
            } else {
                event.reply(new JsonObject().put("status", "ok"));
            }
            async.countDown();
        });

        queueCircuitBreaker.unlockSampleQueues().onComplete(event -> {
            context.assertTrue(event.failed());
            context.assertTrue(event.cause().getMessage().contains("The following queues could not be unlocked: [q2]"));
            async.countDown();
        });

        async.awaitSuccess();
    }

    @Test
    public void testUnlockSampleQueuesFailingStorage(TestContext context) {
        Async async = context.async();

        Mockito.when(queueCircuitBreakerStorage.unlockSampleQueues())
                .thenReturn(Future.failedFuture("unable to lock queues"));

        vertx.eventBus().consumer(Address.redisquesAddress(), (Message<JsonObject> event) ->
                context.fail("Redisques should not have been called when the storage failed"));

        queueCircuitBreaker.unlockSampleQueues().onComplete(event -> {
            context.assertTrue(event.failed());
            context.assertTrue(event.cause().getMessage().contains("unable to lock queues"));
            async.complete();
        });
        async.awaitSuccess();
    }

    @Test
    public void testCloseCircuit(TestContext context) {
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash", "my-metric-1"));

        Mockito.when(queueCircuitBreakerStorage.closeCircuit(any(PatternAndCircuitHash.class)))
                .thenReturn(Future.succeededFuture());

        queueCircuitBreaker.closeCircuit(req).onComplete(event -> {
            context.assertTrue(event.succeeded());
            verify(queueCircuitBreakerStorage, times(1)).closeCircuit(any(PatternAndCircuitHash.class));
            async.complete();
        });
        async.awaitSuccess();
    }

    @Test
    public void testCloseCircuitFailingStorage(TestContext context) {
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash", "my-metric-1"));

        Mockito.when(queueCircuitBreakerStorage.closeCircuit(any(PatternAndCircuitHash.class)))
                .thenReturn(Future.failedFuture("unable to close circuit"));

        queueCircuitBreaker.closeCircuit(req).onComplete(event -> {
            context.assertTrue(event.failed());
            context.assertTrue(event.cause().getMessage().contains("unable to close circuit"));
            verify(queueCircuitBreakerStorage, times(1)).closeCircuit(any(PatternAndCircuitHash.class));
            async.complete();
        });
        async.awaitSuccess();
    }

    @Test
    public void testCloseAllCircuits(TestContext context) {
        Async async = context.async();

        Mockito.when(queueCircuitBreakerStorage.closeAllCircuits())
                .thenReturn(Future.succeededFuture());

        queueCircuitBreaker.closeAllCircuits().onComplete(event -> {
            context.assertTrue(event.succeeded());
            verify(queueCircuitBreakerStorage, times(1)).closeAllCircuits();
            async.complete();
        });
        async.awaitSuccess();
    }

    @Test
    public void testCloseAllCircuitsFailingStorage(TestContext context) {
        Async async = context.async();

        Mockito.when(queueCircuitBreakerStorage.closeAllCircuits())
                .thenReturn(Future.failedFuture("unable to close all circuits"));

        queueCircuitBreaker.closeAllCircuits().onComplete(event -> {
            context.assertTrue(event.failed());
            context.assertTrue(event.cause().getMessage().contains("unable to close all circuits"));
            verify(queueCircuitBreakerStorage, times(1)).closeAllCircuits();
            async.complete();
        });
        async.awaitSuccess();
    }

    @Test
    public void testReOpenCircuit(TestContext context) {
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash", "my-metric-1"));

        Mockito.when(queueCircuitBreakerStorage.reOpenCircuit(any(PatternAndCircuitHash.class)))
                .thenReturn(Future.succeededFuture());

        queueCircuitBreaker.reOpenCircuit(req).onComplete(event -> {
            context.assertTrue(event.succeeded());
            verify(queueCircuitBreakerStorage, times(1)).reOpenCircuit(any(PatternAndCircuitHash.class));
            async.complete();
        });
        async.awaitSuccess();
    }

    @Test
    public void testReOpenCircuitFailingStorage(TestContext context) {
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash", "my-metric-1"));

        Mockito.when(queueCircuitBreakerStorage.reOpenCircuit(any(PatternAndCircuitHash.class)))
                .thenReturn(Future.failedFuture("unable to re-open circuit"));

        queueCircuitBreaker.reOpenCircuit(req).onComplete(event -> {
            context.assertTrue(event.failed());
            context.assertTrue(event.cause().getMessage().contains("unable to re-open circuit"));
            verify(queueCircuitBreakerStorage, times(1)).reOpenCircuit(any(PatternAndCircuitHash.class));
            async.complete();
        });
        async.awaitSuccess();
    }
}

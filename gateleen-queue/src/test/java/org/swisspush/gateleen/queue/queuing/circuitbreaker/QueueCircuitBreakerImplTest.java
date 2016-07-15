package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.routing.RuleProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.mockito.Matchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.QueueResponseType.*;

/**
 * Tests for the {@link QueueCircuitBreakerImpl} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class QueueCircuitBreakerImplTest {

    private Vertx vertx;
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
    public void setUp(){
        vertx = Vertx.vertx();
        storage = Mockito.mock(ResourceStorage.class);
        queueCircuitBreakerStorage = Mockito.mock(QueueCircuitBreakerStorage.class);
        ruleProvider = new RuleProvider(vertx, "/path/to/routing/rules", storage, props);
        ruleToCircuitMapping = Mockito.mock(QueueCircuitBreakerRulePatternToCircuitMapping.class);
        configResourceManager = Mockito.spy(new QueueCircuitBreakerConfigurationResourceManager(vertx, storage, "/path/to/circuitbreaker/config"));
        queueCircuitBreaker = Mockito.spy(new QueueCircuitBreakerImpl(vertx, queueCircuitBreakerStorage, ruleProvider, ruleToCircuitMapping, configResourceManager));
    }

    @Test
    public void testHandleQueuedRequest(TestContext context){
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash"));

        Mockito.when(queueCircuitBreakerStorage.getQueueCircuitState(any(PatternAndCircuitHash.class)))
                .thenReturn(Future.succeededFuture(QueueCircuitState.CLOSED));

        Mockito.when(queueCircuitBreakerStorage.lockQueue(anyString(), any(PatternAndCircuitHash.class)))
                .thenReturn(Future.succeededFuture());

        queueCircuitBreaker.handleQueuedRequest("someQueue", req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(QueueCircuitState.CLOSED, event.result());

            Mockito.when(queueCircuitBreakerStorage.getQueueCircuitState(any(PatternAndCircuitHash.class)))
                    .thenReturn(Future.succeededFuture(QueueCircuitState.OPEN));

            queueCircuitBreaker.handleQueuedRequest("someQueue", req).setHandler(event1 -> {
                context.assertTrue(event1.succeeded());
                context.assertEquals(QueueCircuitState.OPEN, event1.result());
                async.complete();
            });
        });
    }

    @Test
    public void testHandleQueuedRequestCallsLockQueueWhenCircuitIsOpen(TestContext context){
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash"));

        Mockito.when(queueCircuitBreakerStorage.getQueueCircuitState(any(PatternAndCircuitHash.class)))
                .thenReturn(Future.succeededFuture(QueueCircuitState.OPEN));

        Mockito.when(queueCircuitBreakerStorage.lockQueue(anyString(), any(PatternAndCircuitHash.class)))
                .thenReturn(Future.succeededFuture());

        queueCircuitBreaker.handleQueuedRequest("someQueue", req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(QueueCircuitState.OPEN, event.result());
            verify(queueCircuitBreaker, times(1)).lockQueue("someQueue", req);
            async.complete();
        });
    }

    @Test
    public void testHandleQueuedRequestDoesNotCallLockQueueWhenCircuitIsNotOpen(TestContext context){
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash"));

        Mockito.when(queueCircuitBreakerStorage.getQueueCircuitState(any(PatternAndCircuitHash.class)))
                .thenReturn(Future.succeededFuture(QueueCircuitState.CLOSED));

        queueCircuitBreaker.handleQueuedRequest("someQueue", req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(QueueCircuitState.CLOSED, event.result());
            verify(queueCircuitBreaker, never()).lockQueue(anyString(), any(HttpRequest.class));

            Mockito.when(queueCircuitBreakerStorage.getQueueCircuitState(any(PatternAndCircuitHash.class)))
                    .thenReturn(Future.succeededFuture(QueueCircuitState.HALF_OPEN));

            queueCircuitBreaker.handleQueuedRequest("someQueue", req).setHandler(event1 -> {
                context.assertTrue(event1.succeeded());
                context.assertEquals(QueueCircuitState.HALF_OPEN, event1.result());
                verify(queueCircuitBreaker, never()).lockQueue(anyString(), any(HttpRequest.class));
                async.complete();
            });
        });
    }

    @Test
    public void testHandleQueuedRequestNoCircuitMapping(TestContext context){
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString())).thenReturn(null);

        queueCircuitBreaker.handleQueuedRequest("someQueue", req).setHandler(event -> {
            context.assertTrue(event.failed());
            context.assertNotNull(event.cause());
            context.assertTrue(event.cause().getMessage().contains("no rule to circuit mapping found for queue"));
            async.complete();
        });
    }

    @Test
    public void testUpdateStatistics(TestContext context){
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash"));

        Mockito.when(queueCircuitBreakerStorage.updateStatistics(any(PatternAndCircuitHash.class),
                anyString(), anyLong(), anyInt(), anyLong(), anyLong(), anyLong(), any(QueueResponseType.class)))
                .thenReturn(Future.succeededFuture(UpdateStatisticsResult.OK));

        queueCircuitBreaker.updateStatistics("someQueue", req, SUCCESS).setHandler(event -> {
            context.assertTrue(event.succeeded());
            verify(queueCircuitBreaker, never()).lockQueue(anyString(), any(HttpRequest.class));
            async.complete();
        });
    }

    @Test
    public void testUpdateStatisticsNoCircuitMapping(TestContext context){
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString())).thenReturn(null);

        queueCircuitBreaker.updateStatistics("someQueueName", req, SUCCESS).setHandler(event -> {
            context.assertTrue(event.failed());
            context.assertNotNull(event.cause());
            context.assertTrue(event.cause().getMessage().contains("no rule to circuit mapping found for queue"));
            verify(queueCircuitBreaker, never()).lockQueue(anyString(), any(HttpRequest.class));
            async.complete();
        });
    }

    @Test
    public void testUpdateStatisticsTriggersQueueLock(TestContext context){
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash"));

        Mockito.when(queueCircuitBreakerStorage.updateStatistics(any(PatternAndCircuitHash.class),
                anyString(), anyLong(), anyInt(), anyLong(), anyLong(), anyLong(), any(QueueResponseType.class)))
                .thenReturn(Future.succeededFuture(UpdateStatisticsResult.OPENED));

        Mockito.when(queueCircuitBreakerStorage.lockQueue(anyString(), any(PatternAndCircuitHash.class)))
                .thenReturn(Future.succeededFuture());

        queueCircuitBreaker.updateStatistics("someQueue", req, SUCCESS).setHandler(event -> {
            context.assertTrue(event.succeeded());
            verify(queueCircuitBreaker, times(1)).lockQueue("someQueue", req);
            async.complete();
        });
    }

    @Test
    public void testQueueLock(TestContext context){
        Async async = context.async(2);
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash"));

        Mockito.when(queueCircuitBreakerStorage.lockQueue(anyString(), any(PatternAndCircuitHash.class)))
                .thenReturn(Future.succeededFuture());

        vertx.eventBus().consumer(Address.redisquesAddress(), (Message<JsonObject> event) -> {
            async.countDown();
            context.assertEquals("putLock", event.body().getString("operation"));
            event.reply(new JsonObject().put("status", "ok"));
        });

        queueCircuitBreaker.lockQueue("someQueue", req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            verify(queueCircuitBreakerStorage, times(1)).lockQueue(anyString(), any(PatternAndCircuitHash.class));
            async.countDown();
        });

        async.awaitSuccess();
    }

    @Test
    public void testQueueLockFailingRedisques(TestContext context){
        Async async = context.async(2);
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash"));

        Mockito.when(queueCircuitBreakerStorage.lockQueue(anyString(), any(PatternAndCircuitHash.class)))
                .thenReturn(Future.succeededFuture());

        vertx.eventBus().consumer(Address.redisquesAddress(), (Message<JsonObject> event) -> {
            async.countDown();
            context.assertEquals("putLock", event.body().getString("operation"));
            event.reply(new JsonObject().put("status", "error"));
        });

        queueCircuitBreaker.lockQueue("someQueue", req).setHandler(event -> {
            context.assertTrue(event.failed());
            context.assertTrue(event.cause().getMessage().contains("failed to lock queue 'someQueue'"));
            verify(queueCircuitBreakerStorage, times(1)).lockQueue(anyString(), any(PatternAndCircuitHash.class));
            async.countDown();
        });

        async.awaitSuccess();
    }

    @Test
    public void testQueueLockFailingStorage(TestContext context){
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash"));

        Mockito.when(queueCircuitBreakerStorage.lockQueue(anyString(), any(PatternAndCircuitHash.class)))
                .thenReturn(Future.failedFuture("queue could not be locked"));

        vertx.eventBus().consumer(Address.redisquesAddress(), (Message<JsonObject> event) -> {
            context.fail("Redisques should not have been called when the storage failed");
        });

        queueCircuitBreaker.lockQueue("someQueue", req).setHandler(event -> {
            context.assertTrue(event.failed());
            context.assertTrue(event.cause().getMessage().contains("queue could not be locked"));
            verify(queueCircuitBreakerStorage, times(1)).lockQueue(anyString(), any(PatternAndCircuitHash.class));
            async.complete();
        });
    }

    @Test
    public void testUnlockNextQueue(TestContext context){
        Async async = context.async(2);

        Mockito.when(queueCircuitBreakerStorage.popQueueToUnlock())
                .thenReturn(Future.succeededFuture("queue_1"));

        vertx.eventBus().consumer(Address.redisquesAddress(), (Message<JsonObject> event) -> {
            async.countDown();
            context.assertEquals("deleteLock", event.body().getString("operation"));
            event.reply(new JsonObject().put("status", "ok"));
        });

        queueCircuitBreaker.unlockNextQueue().setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals("queue_1", event.result());
            verify(queueCircuitBreakerStorage, times(1)).popQueueToUnlock();
            async.countDown();
        });

        async.awaitSuccess();
    }

    @Test
    public void testUnlockNextQueueFailingRedisques(TestContext context){
        Async async = context.async(2);

        Mockito.when(queueCircuitBreakerStorage.popQueueToUnlock())
                .thenReturn(Future.succeededFuture("queue_1"));

        vertx.eventBus().consumer(Address.redisquesAddress(), (Message<JsonObject> event) -> {
            async.countDown();
            context.assertEquals("deleteLock", event.body().getString("operation"));
            event.reply(new JsonObject().put("status", "error"));
        });

        queueCircuitBreaker.unlockNextQueue().setHandler(event -> {
            context.assertTrue(event.failed());
            context.assertTrue(event.cause().getMessage().contains("unable to unlock queue 'queue_1'"));
            verify(queueCircuitBreakerStorage, times(1)).popQueueToUnlock();
            async.countDown();
        });

        async.awaitSuccess();
    }

    @Test
    public void testUnlockNextQueueFailingStorage(TestContext context){
        Async async = context.async();

        Mockito.when(queueCircuitBreakerStorage.popQueueToUnlock())
                .thenReturn(Future.failedFuture("unable to pop queueToUnlock from list"));

        vertx.eventBus().consumer(Address.redisquesAddress(), (Message<JsonObject> event) -> {
            context.fail("Redisques should not have been called when the storage failed");
        });

        queueCircuitBreaker.unlockNextQueue().setHandler(event -> {
            context.assertTrue(event.failed());
            context.assertTrue(event.cause().getMessage().contains("unable to pop queueToUnlock from list"));
            verify(queueCircuitBreakerStorage, times(1)).popQueueToUnlock();
            async.complete();
        });

    }

    @Test
    public void testCloseCircuit(TestContext context){
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash"));

        Mockito.when(queueCircuitBreakerStorage.closeCircuit(any(PatternAndCircuitHash.class)))
                .thenReturn(Future.succeededFuture());

        queueCircuitBreaker.closeCircuit(req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            verify(queueCircuitBreakerStorage, times(1)).closeCircuit(any(PatternAndCircuitHash.class));
            async.complete();
        });
    }

    @Test
    public void testCloseCircuitFailingStorage(TestContext context){
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash"));

        Mockito.when(queueCircuitBreakerStorage.closeCircuit(any(PatternAndCircuitHash.class)))
                .thenReturn(Future.failedFuture("unable to close circuit"));

        queueCircuitBreaker.closeCircuit(req).setHandler(event -> {
            context.assertTrue(event.failed());
            context.assertTrue(event.cause().getMessage().contains("unable to close circuit"));
            verify(queueCircuitBreakerStorage, times(1)).closeCircuit(any(PatternAndCircuitHash.class));
            async.complete();
        });
    }

    @Test
    public void testCloseAllCircuits(TestContext context){
        Async async = context.async();

        Mockito.when(queueCircuitBreakerStorage.closeAllCircuits())
                .thenReturn(Future.succeededFuture());

        queueCircuitBreaker.closeAllCircuits().setHandler(event -> {
            context.assertTrue(event.succeeded());
            verify(queueCircuitBreakerStorage, times(1)).closeAllCircuits();
            async.complete();
        });
    }

    @Test
    public void testCloseAllCircuitsFailingStorage(TestContext context){
        Async async = context.async();

        Mockito.when(queueCircuitBreakerStorage.closeAllCircuits())
                .thenReturn(Future.failedFuture("unable to close all circuits"));

        queueCircuitBreaker.closeAllCircuits().setHandler(event -> {
            context.assertTrue(event.failed());
            context.assertTrue(event.cause().getMessage().contains("unable to close all circuits"));
            verify(queueCircuitBreakerStorage, times(1)).closeAllCircuits();
            async.complete();
        });
    }

    @Test
    public void testReOpenCircuit(TestContext context){
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash"));

        Mockito.when(queueCircuitBreakerStorage.reOpenCircuit(any(PatternAndCircuitHash.class)))
                .thenReturn(Future.succeededFuture());

        queueCircuitBreaker.reOpenCircuit(req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            verify(queueCircuitBreakerStorage, times(1)).reOpenCircuit(any(PatternAndCircuitHash.class));
            async.complete();
        });
    }

    @Test
    public void testReOpenCircuitFailingStorage(TestContext context){
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToCircuitMapping.getCircuitFromRequestUri(anyString()))
                .thenReturn(new PatternAndCircuitHash(Pattern.compile("/someCircuit"), "someCircuitHash"));

        Mockito.when(queueCircuitBreakerStorage.reOpenCircuit(any(PatternAndCircuitHash.class)))
                .thenReturn(Future.failedFuture("unable to re-open circuit"));

        queueCircuitBreaker.reOpenCircuit(req).setHandler(event -> {
            context.assertTrue(event.failed());
            context.assertTrue(event.cause().getMessage().contains("unable to re-open circuit"));
            verify(queueCircuitBreakerStorage, times(1)).reOpenCircuit(any(PatternAndCircuitHash.class));
            async.complete();
        });
    }
}

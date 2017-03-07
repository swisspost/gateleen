package org.swisspush.gateleen.hook.reducedpropagation;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.AdditionalMatchers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.queue.queuing.RequestQueue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;
import static org.swisspush.gateleen.core.util.HttpRequestHeader.CONTENT_LENGTH;
import static org.swisspush.gateleen.core.util.HttpRequestHeader.getInteger;
import static org.swisspush.gateleen.hook.reducedpropagation.ReducedPropagationManager.*;
import static org.swisspush.redisques.util.RedisquesAPI.*;

/**
 * Tests for the {@link ReducedPropagationManager} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class ReducedPropagationManagerTest {

    @org.junit.Rule
    public Timeout rule = Timeout.seconds(5);

    private Vertx vertx;
    private ReducedPropagationStorage reducedPropagationStorage;
    private ReducedPropagationManager manager;
    private RequestQueue requestQueue;
    private InOrder requestQueueInOrder;

    @Before
    public void setUp(){
        vertx = Vertx.vertx();
        reducedPropagationStorage = Mockito.mock(ReducedPropagationStorage.class);
        requestQueue = Mockito.mock(RequestQueue.class);
        requestQueueInOrder = Mockito.inOrder(requestQueue);
        manager = new ReducedPropagationManager(vertx, reducedPropagationStorage, requestQueue);
    }

    @Test
    public void testStartExpiredQueueProcessingInitiallyDisabled(TestContext context){
        Mockito.when(reducedPropagationStorage.removeExpiredQueues(anyLong()))
                .thenReturn(Future.succeededFuture(new ArrayList<>()));
        verify(reducedPropagationStorage, timeout(1000).never()).removeExpiredQueues(anyLong());
    }

    @Test
    public void testStartExpiredQueueProcessing(TestContext context){
        Mockito.when(reducedPropagationStorage.removeExpiredQueues(anyLong()))
                .thenReturn(Future.succeededFuture(new ArrayList<>()));
        manager.startExpiredQueueProcessing(10);
        verify(reducedPropagationStorage, timeout(110).atLeast(10)).removeExpiredQueues(anyLong());
    }

    @Test
    public void testProcessIncomingRequestWithAddQueueStorageError(TestContext context){

        String queue = "queue_boom";

        Mockito.when(reducedPropagationStorage.addQueue(eq(queue), anyLong()))
                .thenReturn(Future.failedFuture("some storage error"));

        long propagationInterval = 500;
        long expectedExpireTS = System.currentTimeMillis() + propagationInterval;

        String originalPayload = "{\"key\":123}";
        MultiMap headers = new CaseInsensitiveHeaders();
        headers.add(CONTENT_LENGTH.getName(), "99");

        manager.processIncomingRequest(HttpMethod.PUT, "targetUri", headers,
                Buffer.buffer(originalPayload), queue, propagationInterval, null).setHandler(event -> {
            context.assertTrue(event.failed());
            context.assertNotNull(event.cause());
            context.assertTrue(event.cause().getMessage().contains("some storage error"));

            // verify that the storage has been called with the original queue name and the correct expiration timestamp
            Mockito.verify(reducedPropagationStorage, timeout(1000).times(1)).addQueue(
                    eq(queue), AdditionalMatchers.geq(expectedExpireTS));

            ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
            ArgumentCaptor<String> queuesCaptor = ArgumentCaptor.forClass(String.class);

            verify(requestQueue, timeout(1000).times(1)).lockedEnqueue(requestCaptor.capture(),
                    queuesCaptor.capture(), eq(LOCK_REQUESTER), any(Handler.class));

            // verify that request has been locked-enqueued in original queue
            HttpRequest originalEnqueue = requestCaptor.getValue();
            context.assertEquals(HttpMethod.PUT, originalEnqueue.getMethod());
            context.assertEquals(99, getInteger(originalEnqueue.getHeaders(), CONTENT_LENGTH)); // Content-Length header should not have changed
            context.assertTrue(Arrays.equals(originalEnqueue.getPayload(), Buffer.buffer(originalPayload).getBytes())); // payload should not have changed

            context.assertEquals(1,queuesCaptor.getAllValues().size());
            context.assertEquals(queue, queuesCaptor.getValue());

            //verify queue request has not been stored to storage
            verify(reducedPropagationStorage, timeout(1000).never()).storeQueueRequest(anyString(), any(JsonObject.class));
        });

    }

    @Test
    public void testProcessIncomingRequestStartingNewTimerAndSuccessfulStoreQueueRequest(TestContext context){
        String queue = "queue_1";
        Mockito.when(reducedPropagationStorage.addQueue(eq(queue), anyLong()))
                .thenReturn(Future.succeededFuture(Boolean.TRUE)); // TRUE => timer started
        Mockito.when(reducedPropagationStorage.storeQueueRequest(eq(queue), any(JsonObject.class)))
                .thenReturn(Future.succeededFuture());

        long propagationInterval = 500;
        long expectedExpireTS = System.currentTimeMillis() + propagationInterval;

        String targetUri = "/the/target/uri";
        String originalPayload = "{\"key\":123}";
        MultiMap headers = new CaseInsensitiveHeaders();
        headers.add(CONTENT_LENGTH.getName(), "99");

        manager.processIncomingRequest(HttpMethod.PUT, targetUri, headers,
                Buffer.buffer(originalPayload), queue, propagationInterval, null).setHandler(event -> {
            context.assertTrue(event.succeeded());

            // verify that the storage has been called with the original queue name and the correct expiration timestamp
            Mockito.verify(reducedPropagationStorage, timeout(1000).times(1)).addQueue(
                    eq(queue), AdditionalMatchers.geq(expectedExpireTS));

            ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
            ArgumentCaptor<String> queuesCaptor = ArgumentCaptor.forClass(String.class);

            verify(requestQueue, timeout(1000).times(1)).lockedEnqueue(requestCaptor.capture(),
                    queuesCaptor.capture(), eq(LOCK_REQUESTER), any(Handler.class));

            List<HttpRequest> requests = requestCaptor.getAllValues();
            List<String> queues = queuesCaptor.getAllValues();

            context.assertEquals(1, requests.size());
            context.assertEquals(1, queues.size());

            // verify that request has been locked-enqueued in original queue
            HttpRequest originalEnqueue = requests.get(0);
            context.assertEquals(HttpMethod.PUT, originalEnqueue.getMethod());
            context.assertEquals(targetUri, originalEnqueue.getUri());
            context.assertEquals(99, getInteger(originalEnqueue.getHeaders(), CONTENT_LENGTH)); // Content-Length header should not have changed
            context.assertTrue(Arrays.equals(originalEnqueue.getPayload(), Buffer.buffer(originalPayload).getBytes())); // payload should not have changed
            context.assertEquals(queue, queues.get(0));

            //verify queue request (without payload) has been stored to storage
            MultiMap headersCopy = new CaseInsensitiveHeaders().addAll(headers);
            headersCopy.set(CONTENT_LENGTH.getName(), "0");
            HttpRequest expectedRequest = new HttpRequest(HttpMethod.PUT, targetUri, headersCopy, null);
            verify(reducedPropagationStorage, timeout(1000).times(1)).storeQueueRequest(eq(queue), eq(expectedRequest.toJsonObject()));
        });
    }

    @Test
    public void testProcessIncomingRequestStartingNewTimerAndFailingStoreQueueRequest(TestContext context){
        String queue = "queue_1";
        Mockito.when(reducedPropagationStorage.addQueue(eq(queue), anyLong()))
                .thenReturn(Future.succeededFuture(Boolean.TRUE)); // TRUE => timer started
        Mockito.when(reducedPropagationStorage.storeQueueRequest(eq(queue), any(JsonObject.class)))
                .thenReturn(Future.failedFuture("Boom"));

        long propagationInterval = 500;
        long expectedExpireTS = System.currentTimeMillis() + propagationInterval;

        String targetUri = "/the/target/uri";
        String originalPayload = "{\"key\":123}";
        MultiMap headers = new CaseInsensitiveHeaders();
        headers.add(CONTENT_LENGTH.getName(), "99");

        manager.processIncomingRequest(HttpMethod.PUT, targetUri, headers,
                Buffer.buffer(originalPayload), queue, propagationInterval, null).setHandler(event -> {
            context.assertTrue(event.failed());
            context.assertEquals("Boom", event.cause().getMessage());

            // verify that the storage has been called with the original queue name and the correct expiration timestamp
            Mockito.verify(reducedPropagationStorage, timeout(1000).times(1)).addQueue(
                    eq(queue), AdditionalMatchers.geq(expectedExpireTS));

            ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
            ArgumentCaptor<String> queuesCaptor = ArgumentCaptor.forClass(String.class);

            verify(requestQueue, timeout(1000).times(1)).lockedEnqueue(requestCaptor.capture(),
                    queuesCaptor.capture(), eq(LOCK_REQUESTER), any(Handler.class));

            List<HttpRequest> requests = requestCaptor.getAllValues();
            List<String> queues = queuesCaptor.getAllValues();

            context.assertEquals(1, requests.size());
            context.assertEquals(1, queues.size());

            // verify that request has been locked-enqueued in original queue
            HttpRequest originalEnqueue = requests.get(0);
            context.assertEquals(HttpMethod.PUT, originalEnqueue.getMethod());
            context.assertEquals(targetUri, originalEnqueue.getUri());
            context.assertEquals(99, getInteger(originalEnqueue.getHeaders(), CONTENT_LENGTH)); // Content-Length header should not have changed
            context.assertTrue(Arrays.equals(originalEnqueue.getPayload(), Buffer.buffer(originalPayload).getBytes())); // payload should not have changed
            context.assertEquals(queue, queues.get(0));

            //verify queue request (without payload) has been stored to storage
            MultiMap headersCopy = new CaseInsensitiveHeaders().addAll(headers);
            headersCopy.set(CONTENT_LENGTH.getName(), "0");
            HttpRequest expectedRequest = new HttpRequest(HttpMethod.PUT, targetUri, headersCopy, null);
            verify(reducedPropagationStorage, timeout(1000).times(1)).storeQueueRequest(eq(queue), eq(expectedRequest.toJsonObject()));
        });
    }

    @Test
    public void testProcessIncomingRequestStartingExistingTimer(TestContext context){
        String queue = "queue_1";
        Mockito.when(reducedPropagationStorage.addQueue(eq(queue), anyLong()))
                .thenReturn(Future.succeededFuture(Boolean.FALSE)); // FALSE => timer already exists

        long propagationInterval = 500;
        long expectedExpireTS = System.currentTimeMillis() + propagationInterval;

        String targetUri = "/the/target/uri";
        String originalPayload = "{\"key\":123}";
        MultiMap headers = new CaseInsensitiveHeaders();
        headers.add(CONTENT_LENGTH.getName(), "99");

        manager.processIncomingRequest(HttpMethod.PUT, targetUri, headers,
                Buffer.buffer(originalPayload), queue, propagationInterval, null).setHandler(event -> {
            context.assertTrue(event.succeeded());

            // verify that the storage has been called with the original queue name and the correct expiration timestamp
            Mockito.verify(reducedPropagationStorage, timeout(1000).times(1)).addQueue(
                    eq(queue), AdditionalMatchers.geq(expectedExpireTS));

            ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
            ArgumentCaptor<String> queuesCaptor = ArgumentCaptor.forClass(String.class);

            verify(requestQueue, timeout(1000).times(1)).lockedEnqueue(requestCaptor.capture(),
                    queuesCaptor.capture(), eq(LOCK_REQUESTER), any(Handler.class));

            // verify that request has been locked-enqueued in original queue
            HttpRequest originalEnqueue = requestCaptor.getValue();
            context.assertEquals(HttpMethod.PUT, originalEnqueue.getMethod());
            context.assertEquals(targetUri, originalEnqueue.getUri());
            context.assertEquals(99, getInteger(originalEnqueue.getHeaders(), CONTENT_LENGTH)); // Content-Length header should not have changed
            context.assertTrue(Arrays.equals(originalEnqueue.getPayload(), Buffer.buffer(originalPayload).getBytes())); // payload should not have changed

            context.assertEquals(1, queuesCaptor.getAllValues().size());
            context.assertEquals(queue, queuesCaptor.getValue());

            //verify queue request has not been stored to storage
            verify(reducedPropagationStorage, timeout(1000).never()).storeQueueRequest(anyString(), any(JsonObject.class));
        });
    }

    @Test
    public void testExpiredQueueProcessingSuccess(TestContext context){
        Mockito.when(requestQueue.deleteLock(anyString())).thenReturn(Future.succeededFuture());
        Mockito.when(requestQueue.deleteAllQueueItems(anyString(), anyBoolean())).thenReturn(Future.succeededFuture());

        String expiredQueue = "myExpiredQueue";
        String managerQueue = MANAGER_QUEUE_PREFIX + expiredQueue;
        vertx.eventBus().send(PROCESSOR_ADDRESS, new JsonObject().put(QUEUE, expiredQueue).put(MANAGER_QUEUE, managerQueue), (Handler<AsyncResult<Message<JsonObject>>>) event1 -> {
            context.assertEquals(OK, event1.result().body().getString(STATUS));
            context.assertEquals("Successfully unlocked manager queue manager_myExpiredQueue and deleted all queue items of queue myExpiredQueue", event1.result().body().getString(MESSAGE));

            requestQueueInOrder.verify(requestQueue, times(1)).deleteLock(eq(managerQueue));
            requestQueueInOrder.verify(requestQueue, times(1)).deleteAllQueueItems(eq(expiredQueue), eq(Boolean.TRUE));
        });
    }

    @Test
    public void testExpiredQueueProcessingFailedToDeleteLockOfManagerQueue(TestContext context){
        Mockito.when(requestQueue.deleteLock(anyString())).thenReturn(Future.failedFuture("boom"));
        Mockito.when(requestQueue.deleteAllQueueItems(anyString(), anyBoolean())).thenReturn(Future.succeededFuture());

        String expiredQueue = "myExpiredQueue";
        String managerQueue = MANAGER_QUEUE_PREFIX + expiredQueue;
        vertx.eventBus().send(PROCESSOR_ADDRESS, new JsonObject().put(QUEUE, expiredQueue).put(MANAGER_QUEUE, managerQueue), (Handler<AsyncResult<Message<JsonObject>>>) event1 -> {
            context.assertEquals(ERROR, event1.result().body().getString(STATUS));
            context.assertEquals("boom", event1.result().body().getString(MESSAGE));

            requestQueueInOrder.verify(requestQueue, times(1)).deleteLock(eq(managerQueue));
            requestQueueInOrder.verify(requestQueue, never()).deleteAllQueueItems(eq(expiredQueue), eq(Boolean.TRUE));
        });
    }

    @Test
    public void testExpiredQueueProcessingFailedToDeleteAllQueueItems(TestContext context){
        Mockito.when(requestQueue.deleteLock(anyString())).thenReturn(Future.succeededFuture());
        Mockito.when(requestQueue.deleteAllQueueItems(anyString(), anyBoolean())).thenReturn(Future.failedFuture("deleteAllQueueItems boom"));

        String expiredQueue = "myExpiredQueue";
        String managerQueue = MANAGER_QUEUE_PREFIX + expiredQueue;
        vertx.eventBus().send(PROCESSOR_ADDRESS, new JsonObject().put(QUEUE, expiredQueue).put(MANAGER_QUEUE, managerQueue), (Handler<AsyncResult<Message<JsonObject>>>) event1 -> {
            context.assertEquals(ERROR, event1.result().body().getString(STATUS));
            context.assertEquals("deleteAllQueueItems boom", event1.result().body().getString(MESSAGE));

            requestQueueInOrder.verify(requestQueue, times(1)).deleteLock(eq(managerQueue));
            requestQueueInOrder.verify(requestQueue, times(1)).deleteAllQueueItems(eq(expiredQueue), eq(Boolean.TRUE));
        });
    }
}

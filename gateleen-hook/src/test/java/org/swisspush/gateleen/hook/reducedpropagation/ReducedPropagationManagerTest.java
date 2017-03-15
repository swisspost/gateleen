package org.swisspush.gateleen.hook.reducedpropagation;

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

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
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
    public Timeout rule = Timeout.seconds(50);

    private Vertx vertx;
    private ReducedPropagationStorage reducedPropagationStorage;
    private ReducedPropagationManager manager;
    private RequestQueue requestQueue;
    private InOrder requestQueueInOrder;

    @Before
    public void setUp() {
        vertx = Vertx.vertx();
        reducedPropagationStorage = Mockito.mock(ReducedPropagationStorage.class);
        requestQueue = Mockito.mock(RequestQueue.class);
        requestQueueInOrder = Mockito.inOrder(requestQueue);
        manager = new ReducedPropagationManager(vertx, reducedPropagationStorage, requestQueue);
    }

    @Test
    public void testStartExpiredQueueProcessingInitiallyDisabled(TestContext context) {
        Mockito.when(reducedPropagationStorage.removeExpiredQueues(anyLong()))
                .thenReturn(Future.succeededFuture(new ArrayList<>()));
        verify(reducedPropagationStorage, timeout(1000).never()).removeExpiredQueues(anyLong());
    }

    @Test
    public void testStartExpiredQueueProcessing(TestContext context) {
        Mockito.when(reducedPropagationStorage.removeExpiredQueues(anyLong()))
                .thenReturn(Future.succeededFuture(new ArrayList<>()));
        manager.startExpiredQueueProcessing(10);
        verify(reducedPropagationStorage, timeout(110).atLeast(10)).removeExpiredQueues(anyLong());
    }

    @Test
    public void testProcessIncomingRequestWithAddQueueStorageError(TestContext context) {

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

            context.assertEquals(1, queuesCaptor.getAllValues().size());
            context.assertEquals(queue, queuesCaptor.getValue());

            //verify queue request has not been stored to storage
            verify(reducedPropagationStorage, timeout(1000).never()).storeQueueRequest(anyString(), any(JsonObject.class));
        });

    }

    @Test
    public void testProcessIncomingRequestStartingNewTimerAndSuccessfulStoreQueueRequest(TestContext context) {
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
    public void testProcessIncomingRequestStartingNewTimerAndFailingStoreQueueRequest(TestContext context) {
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
    public void testProcessIncomingRequestStartingExistingTimer(TestContext context) {
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
    public void testExpiredQueueProcessingInvalidQueue(TestContext context) {
        Async async = context.async();

        String expectedErrorMessage = "Tried to process an expired queue without a valid queue name. Going to stop here";

        // send event bus message with 'null' as queue name
        vertx.eventBus().send(PROCESSOR_ADDRESS, null, (Handler<AsyncResult<Message<JsonObject>>>) nullQueueEvent -> {
            context.assertEquals(ERROR, nullQueueEvent.result().body().getString(STATUS));
            context.assertEquals(expectedErrorMessage, nullQueueEvent.result().body().getString(MESSAGE));

            // send event bus message with an empty string as queue name
            vertx.eventBus().send(PROCESSOR_ADDRESS, "", (Handler<AsyncResult<Message<JsonObject>>>) emptyQueueEvent -> {
                context.assertEquals(ERROR, emptyQueueEvent.result().body().getString(STATUS));
                context.assertEquals(expectedErrorMessage, emptyQueueEvent.result().body().getString(MESSAGE));
                async.complete();
            });
        });
    }

    @Test
    public void testExpiredQueueProcessingGetQueueRequestFailure(TestContext context){
        Async async = context.async();
        Mockito.when(reducedPropagationStorage.getQueueRequest(anyString())).thenReturn(Future.failedFuture("boom: getQueueRequest failed"));

        String expiredQueue = "myExpiredQueue";
        vertx.eventBus().send(PROCESSOR_ADDRESS, expiredQueue, (Handler<AsyncResult<Message<JsonObject>>>) event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(ERROR, event.result().body().getString(STATUS));
            context.assertEquals("boom: getQueueRequest failed", event.result().body().getString(MESSAGE));

            verify(reducedPropagationStorage, timeout(1000).times(1)).getQueueRequest(eq(expiredQueue));
            verify(reducedPropagationStorage, timeout(1000).never()).removeQueueRequest(anyString());
            verifyZeroInteractions(requestQueue);

            async.complete();
        });
    }

    @Test
    public void testExpiredQueueProcessingGetQueueRequestReturnsNull(TestContext context){
        Async async = context.async();
        Mockito.when(reducedPropagationStorage.getQueueRequest(anyString())).thenReturn(Future.succeededFuture(null));

        String expiredQueue = "myExpiredQueue";
        vertx.eventBus().send(PROCESSOR_ADDRESS, expiredQueue, (Handler<AsyncResult<Message<JsonObject>>>) event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(ERROR, event.result().body().getString(STATUS));
            context.assertEquals("stored queue request for queue 'myExpiredQueue' is null", event.result().body().getString(MESSAGE));

            verify(reducedPropagationStorage, timeout(1000).times(1)).getQueueRequest(eq(expiredQueue));
            verify(reducedPropagationStorage, timeout(1000).never()).removeQueueRequest(anyString());
            verifyZeroInteractions(requestQueue);

            async.complete();
        });
    }

    @Test
    public void testExpiredQueueProcessingDeleteAllQueueItemsOfManagerQueueFailure(TestContext context){
        Async async = context.async();
        Mockito.when(reducedPropagationStorage.getQueueRequest(anyString())).thenReturn(Future.succeededFuture(new JsonObject()));
        Mockito.when(requestQueue.deleteAllQueueItems(anyString(), anyBoolean())).thenReturn(Future.failedFuture("boom: deleteAllQueueItems failed"));

        String expiredQueue = "myExpiredQueue";
        String managerQueue = MANAGER_QUEUE_PREFIX + expiredQueue;

        vertx.eventBus().send(PROCESSOR_ADDRESS, expiredQueue, (Handler<AsyncResult<Message<JsonObject>>>) event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(ERROR, event.result().body().getString(STATUS));
            context.assertEquals("boom: deleteAllQueueItems failed", event.result().body().getString(MESSAGE));

            verify(reducedPropagationStorage, timeout(1000).times(1)).getQueueRequest(eq(expiredQueue));
            verify(requestQueue, timeout(1000).times(1)).deleteAllQueueItems(eq(managerQueue), eq(false));

            verify(requestQueue, timeout(1000).never()).enqueueFuture(any(), any());
            verify(reducedPropagationStorage, timeout(1000).never()).removeQueueRequest(anyString());
            verify(requestQueue, timeout(1000).never()).deleteAllQueueItems(eq(expiredQueue), eq(true));

            async.complete();
        });
    }

    @Test
    public void testExpiredQueueProcessingInvalidStoredQueueRequest(TestContext context){
        Async async = context.async();
        Mockito.when(reducedPropagationStorage.getQueueRequest(anyString())).thenReturn(Future.succeededFuture(new JsonObject().put("method", "PUT")));
        Mockito.when(requestQueue.deleteAllQueueItems(anyString(), anyBoolean())).thenReturn(Future.succeededFuture());

        String expiredQueue = "myExpiredQueue";
        String managerQueue = MANAGER_QUEUE_PREFIX + expiredQueue;

        vertx.eventBus().send(PROCESSOR_ADDRESS, expiredQueue, (Handler<AsyncResult<Message<JsonObject>>>) event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(ERROR, event.result().body().getString(STATUS));
            context.assertEquals("Request fields 'uri' and 'method' must be set", event.result().body().getString(MESSAGE));

            verify(reducedPropagationStorage, timeout(1000).times(1)).getQueueRequest(eq(expiredQueue));
            verify(requestQueue, timeout(1000).times(1)).deleteAllQueueItems(eq(managerQueue), eq(false));

            verify(requestQueue, timeout(1000).never()).enqueueFuture(any(), any());
            verify(reducedPropagationStorage, timeout(1000).never()).removeQueueRequest(anyString());
            verify(requestQueue, timeout(1000).never()).deleteAllQueueItems(eq(expiredQueue), eq(true));

            async.complete();
        });
    }

    @Test
    public void testExpiredQueueProcessingEnqueueIntoManagerQueueFailure(TestContext context){
        Async async = context.async();

        JsonObject requestJsonObject = new HttpRequest(HttpMethod.PUT, "/my/uri", new CaseInsensitiveHeaders(), null).toJsonObject();

        Mockito.when(reducedPropagationStorage.getQueueRequest(anyString())).thenReturn(Future.succeededFuture(requestJsonObject));
        Mockito.when(requestQueue.deleteAllQueueItems(anyString(), anyBoolean())).thenReturn(Future.succeededFuture());
        Mockito.when(requestQueue.enqueueFuture(any(), anyString())).thenReturn(Future.failedFuture("boom: enqueueFuture failed"));

        String expiredQueue = "myExpiredQueue";
        String managerQueue = MANAGER_QUEUE_PREFIX + expiredQueue;

        vertx.eventBus().send(PROCESSOR_ADDRESS, expiredQueue, (Handler<AsyncResult<Message<JsonObject>>>) event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(ERROR, event.result().body().getString(STATUS));
            context.assertEquals("boom: enqueueFuture failed", event.result().body().getString(MESSAGE));

            verify(reducedPropagationStorage, timeout(1000).times(1)).getQueueRequest(eq(expiredQueue));
            verify(requestQueue, timeout(1000).times(1)).deleteAllQueueItems(eq(managerQueue), eq(false));
            verify(requestQueue, timeout(1000).times(1)).enqueueFuture(any(), eq(managerQueue));

            verify(reducedPropagationStorage, timeout(1000).never()).removeQueueRequest(anyString());
            verify(requestQueue, timeout(1000).never()).deleteAllQueueItems(eq(expiredQueue), eq(true));

            async.complete();
        });
    }

    @Test
    public void testExpiredQueueProcessingDeleteAllQueueItemsAndDeleteLockOfQueueFailure(TestContext context){
        Async async = context.async();

        JsonObject requestJsonObject = new HttpRequest(HttpMethod.PUT, "/my/uri", new CaseInsensitiveHeaders(), null).toJsonObject();

        String expiredQueue = "myExpiredQueue";
        String managerQueue = MANAGER_QUEUE_PREFIX + expiredQueue;

        Mockito.when(reducedPropagationStorage.getQueueRequest(anyString())).thenReturn(Future.succeededFuture(requestJsonObject));
        Mockito.when(requestQueue.deleteAllQueueItems(eq(managerQueue), anyBoolean())).thenReturn(Future.succeededFuture());
        Mockito.when(requestQueue.enqueueFuture(any(), anyString())).thenReturn(Future.succeededFuture());
        Mockito.when(reducedPropagationStorage.removeQueueRequest(eq(expiredQueue))).thenReturn(Future.succeededFuture());
        Mockito.when(requestQueue.deleteAllQueueItems(eq(expiredQueue), anyBoolean())).thenReturn(Future.failedFuture("boom: deleteAllQueueItems failed"));

        vertx.eventBus().send(PROCESSOR_ADDRESS, expiredQueue, (Handler<AsyncResult<Message<JsonObject>>>) event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(ERROR, event.result().body().getString(STATUS));
            context.assertEquals("boom: deleteAllQueueItems failed", event.result().body().getString(MESSAGE));

            verify(reducedPropagationStorage, timeout(1000).times(1)).getQueueRequest(eq(expiredQueue));
            verify(requestQueue, timeout(1000).times(1)).deleteAllQueueItems(eq(managerQueue), eq(false));
            verify(requestQueue, timeout(1000).times(1)).enqueueFuture(any(), eq(managerQueue));
            verify(reducedPropagationStorage, timeout(1000).times(1)).removeQueueRequest(eq(expiredQueue));
            verify(requestQueue, timeout(1000).times(1)).deleteAllQueueItems(eq(expiredQueue), eq(true));

            async.complete();
        });
    }

    @Test
    public void testExpiredQueueProcessingDeleteAllQueueItemsAndDeleteLockOfQueueSuccess(TestContext context){
        Async async = context.async();

        JsonObject requestJsonObject = new HttpRequest(HttpMethod.PUT, "/my/uri", new CaseInsensitiveHeaders(), null).toJsonObject();

        String expiredQueue = "myExpiredQueue";
        String managerQueue = MANAGER_QUEUE_PREFIX + expiredQueue;

        Mockito.when(reducedPropagationStorage.getQueueRequest(anyString())).thenReturn(Future.succeededFuture(requestJsonObject));
        Mockito.when(requestQueue.deleteAllQueueItems(eq(managerQueue), anyBoolean())).thenReturn(Future.succeededFuture());
        Mockito.when(requestQueue.enqueueFuture(any(), anyString())).thenReturn(Future.succeededFuture());
        Mockito.when(reducedPropagationStorage.removeQueueRequest(eq(expiredQueue))).thenReturn(Future.succeededFuture());
        Mockito.when(requestQueue.deleteAllQueueItems(eq(expiredQueue), anyBoolean())).thenReturn(Future.succeededFuture());

        vertx.eventBus().send(PROCESSOR_ADDRESS, expiredQueue, (Handler<AsyncResult<Message<JsonObject>>>) event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(OK, event.result().body().getString(STATUS));
            context.assertEquals("Successfully deleted lock and all queue items of queue myExpiredQueue", event.result().body().getString(MESSAGE));

            verify(reducedPropagationStorage, timeout(1000).times(1)).getQueueRequest(eq(expiredQueue));
            verify(requestQueue, timeout(1000).times(1)).deleteAllQueueItems(eq(managerQueue), eq(false));
            verify(requestQueue, timeout(1000).times(1)).enqueueFuture(any(), eq(managerQueue));
            verify(reducedPropagationStorage, timeout(1000).times(1)).removeQueueRequest(eq(expiredQueue));
            verify(requestQueue, timeout(1000).times(1)).deleteAllQueueItems(eq(expiredQueue), eq(true));

            async.complete();
        });
    }

}

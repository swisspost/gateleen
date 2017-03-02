package org.swisspush.gateleen.hook.reducedpropagation;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.AdditionalMatchers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.queue.queuing.RequestQueue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.mockito.Matchers.*;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.swisspush.gateleen.core.util.HttpRequestHeader.CONTENT_LENGTH;
import static org.swisspush.gateleen.core.util.HttpRequestHeader.getInteger;
import static org.swisspush.gateleen.hook.reducedpropagation.ReducedPropagationManager.LOCK_REQUESTER;
import static org.swisspush.gateleen.hook.reducedpropagation.ReducedPropagationManager.MANAGER_QUEUE_PREFIX;

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

    @Before
    public void setUp(){
        vertx = Vertx.vertx();
        reducedPropagationStorage = Mockito.mock(ReducedPropagationStorage.class);
        requestQueue = Mockito.mock(RequestQueue.class);
        manager = new ReducedPropagationManager(vertx, reducedPropagationStorage, requestQueue, 100);
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
    public void testProcessIncomingRequestWithStorageError(TestContext context){

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
        });

    }

    @Test
    public void testProcessIncomingRequestStartingNewTimer(TestContext context){
        String queue = "queue_1";
        Mockito.when(reducedPropagationStorage.addQueue(eq(queue), anyLong()))
                .thenReturn(Future.succeededFuture(Boolean.TRUE)); // TRUE => timer started

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

            verify(requestQueue, timeout(1000).times(2)).lockedEnqueue(requestCaptor.capture(),
                    queuesCaptor.capture(), eq(LOCK_REQUESTER), any(Handler.class));

            List<HttpRequest> requests = requestCaptor.getAllValues();
            List<String> queues = queuesCaptor.getAllValues();

            context.assertEquals(2, requests.size());
            context.assertEquals(2, queues.size());

            // verify that request has been locked-enqueued in original queue
            HttpRequest originalEnqueue = requests.get(0);
            context.assertEquals(HttpMethod.PUT, originalEnqueue.getMethod());
            context.assertEquals(targetUri, originalEnqueue.getUri());
            context.assertEquals(99, getInteger(originalEnqueue.getHeaders(), CONTENT_LENGTH)); // Content-Length header should not have changed
            context.assertTrue(Arrays.equals(originalEnqueue.getPayload(), Buffer.buffer(originalPayload).getBytes())); // payload should not have changed
            context.assertEquals(queue, queues.get(0));

            // verify that the request has been locked-enqueued in the manager queue and without payload
            HttpRequest managerEnqueue = requests.get(1);
            context.assertEquals(HttpMethod.PUT, managerEnqueue.getMethod());
            context.assertEquals(targetUri, managerEnqueue.getUri());
            context.assertEquals(0, getInteger(managerEnqueue.getHeaders(), CONTENT_LENGTH)); // Content-Length header should have changed
            context.assertTrue(Arrays.equals(managerEnqueue.getPayload(), new byte[0])); // should not be original payload anymore
            context.assertEquals(MANAGER_QUEUE_PREFIX + queue, queues.get(1));
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
        });
    }
}

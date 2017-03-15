package org.swisspush.gateleen.queue.queuing;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
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
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.redisques.util.RedisquesAPI;

import static org.mockito.Matchers.eq;
import static org.swisspush.redisques.util.RedisquesAPI.*;

/**
 * Tests for the {@link QueueClient} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class QueueClientTest {

    private Vertx vertx;
    private MonitoringHandler monitoringHandler;
    private QueueClient queueClient;

    @org.junit.Rule
    public Timeout rule = Timeout.seconds(5);

    @Before
    public void setUp(){
        vertx = Vertx.vertx();
        monitoringHandler = Mockito.mock(MonitoringHandler.class);
        queueClient = new QueueClient(vertx, monitoringHandler);
    }

    @Test
    public void testEnqueueFuture(TestContext context){
        Async async = context.async();

        /*
         * consume event bus messages directed to redisques and verify message content.
         * reply with 'success' for enqueuing
         */
        vertx.eventBus().localConsumer(Address.redisquesAddress(), (Handler<Message<JsonObject>>) message -> {
            validateMessage(context, message, QueueOperation.enqueue, "myQueue");
            message.reply(new JsonObject().put(STATUS, OK));
        });

        HttpRequest request = new HttpRequest(HttpMethod.PUT, "/targetUri", new CaseInsensitiveHeaders(), Buffer.buffer("{\"key\":\"value\"}").getBytes());
        queueClient.enqueueFuture(request, "myQueue").setHandler(event -> {
            context.assertTrue(event.succeeded());
            async.complete();
        });

        Mockito.verify(monitoringHandler, Mockito.timeout(1000).times(1)).updateLastUsedQueueSizeInformation(eq("myQueue"));
        Mockito.verify(monitoringHandler, Mockito.timeout(1000).times(1)).updateEnqueue();
    }

    @Test
    public void testEnqueueFutureNotUpdatingMonitoringHandlerOnRedisquesFail(TestContext context){
        Async async = context.async();

        /*
         * consume event bus messages directed to redisques and verify message content.
         * reply with 'failure' for enqueuing
         */
        vertx.eventBus().localConsumer(Address.redisquesAddress(), (Handler<Message<JsonObject>>) message -> {
            validateMessage(context, message, QueueOperation.enqueue, "myQueue");
            message.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, "enqueue:boom"));
        });

        HttpRequest request = new HttpRequest(HttpMethod.PUT, "/targetUri", new CaseInsensitiveHeaders(), Buffer.buffer("{\"key\":\"value\"}").getBytes());
        queueClient.enqueueFuture(request, "myQueue").setHandler(event -> {
            context.assertFalse(event.succeeded());
            context.assertEquals("enqueue:boom", event.cause().getMessage());
            async.complete();
        });

        // since redisques answered with a 'failure', the monitoringHandler should not be called
        Mockito.verifyZeroInteractions(monitoringHandler);
    }

    @Test
    public void testDeletelock(TestContext context){
        Async async = context.async();

        /*
         * consume event bus messages directed to redisques and verify message content.
         * reply with 'success' for unlock
         */
        vertx.eventBus().localConsumer(Address.redisquesAddress(), (Handler<Message<JsonObject>>) message -> {
            validateMessage(context, message, QueueOperation.deleteLock, "myQueueToUnlock");
            message.reply(new JsonObject().put(STATUS, OK));
        });

        queueClient.deleteLock("myQueueToUnlock").setHandler(event -> {
            context.assertTrue(event.succeeded());
            async.complete();
        });
    }

    @Test
    public void testDeleteLockWithRedisquesFail(TestContext context){
        Async async = context.async();

        /*
         * consume event bus messages directed to redisques and verify message content.
         * reply with 'failed' for unlock
         */
        vertx.eventBus().localConsumer(Address.redisquesAddress(), (Handler<Message<JsonObject>>) message -> {
            validateMessage(context, message, QueueOperation.deleteLock, "myQueueToUnlock");
            message.reply(new JsonObject().put(STATUS, ERROR));
        });

        queueClient.deleteLock("myQueueToUnlock").setHandler(event -> {
            context.assertTrue(event.failed());
            context.assertTrue(event.cause().getMessage().contains("Failed to delete lock for queue myQueueToUnlock"));
            async.complete();
        });
    }

    @Test
    public void testDeleteAllQueueItemsDoUnlock(TestContext context){
        Async async = context.async();

        /*
         * consume event bus messages directed to redisques and verify message content.
         * reply with 'success' for deleteAllQueueItems
         */
        vertx.eventBus().localConsumer(Address.redisquesAddress(), (Handler<Message<JsonObject>>) message -> {
            validateMessage(context, message, QueueOperation.deleteAllQueueItems, "myQueueToDeleteAndUnlock", true);
            message.reply(new JsonObject().put(STATUS, OK));
        });

        queueClient.deleteAllQueueItems("myQueueToDeleteAndUnlock", true).setHandler(event -> {
            context.assertTrue(event.succeeded());
            async.complete();
        });
    }

    @Test
    public void testDeleteAllQueueItemsDoNotUnlock(TestContext context){
        Async async = context.async();

        /*
         * consume event bus messages directed to redisques and verify message content.
         * reply with 'success' for deleteAllQueueItems
         */
        vertx.eventBus().localConsumer(Address.redisquesAddress(), (Handler<Message<JsonObject>>) message -> {
            validateMessage(context, message, QueueOperation.deleteAllQueueItems, "myQueueToDelete", false);
            message.reply(new JsonObject().put(STATUS, OK));
        });

        queueClient.deleteAllQueueItems("myQueueToDelete", false).setHandler(event -> {
            context.assertTrue(event.succeeded());
            async.complete();
        });
    }

    /**
     * Redisques returns a status 'ERROR' when nothing could be found to delete. However the result is the same,
     * the resource to delete is gone.
     *
     * @param context
     */
    @Test
    public void testDeleteAllQueueItemsWithRedisquesReturningError(TestContext context){
        Async async = context.async();

        /*
         * consume event bus messages directed to redisques and verify message content.
         * reply with 'Error' for deleteAllQueueItems
         */
        vertx.eventBus().localConsumer(Address.redisquesAddress(), (Handler<Message<JsonObject>>) message -> {
            validateMessage(context, message, QueueOperation.deleteAllQueueItems, "myQueueToDeleteAndUnlock", true);
            message.reply(new JsonObject().put(STATUS, ERROR));
        });

        queueClient.deleteAllQueueItems("myQueueToDeleteAndUnlock", true).setHandler(event -> {
            context.assertTrue(event.succeeded());
            async.complete();
        });
    }

    @Test
    public void testDeleteAllQueueItemsWithRedisquesFail(TestContext context){
        Async async = context.async();

        /*
         * consume event bus messages directed to redisques and verify message content.
         * reply with 'failed' for deleteAllQueueItems
         */
        vertx.eventBus().localConsumer(Address.redisquesAddress(), (Handler<Message<JsonObject>>) message -> {
            validateMessage(context, message, QueueOperation.deleteAllQueueItems, "myQueueToDeleteAndUnlock", true);
            message.fail(0, "boom");
        });

        queueClient.deleteAllQueueItems("myQueueToDeleteAndUnlock", true).setHandler(event -> {
            context.assertTrue(event.failed());
            context.assertTrue(event.cause().getMessage().contains("Failed to delete all queue items for queue myQueueToDeleteAndUnlock with unlock true"));
            async.complete();
        });
    }

    @Test
    public void testLockedEnqueue(TestContext context){
        Async async = context.async();

        /*
         * consume event bus messages directed to redisques and verify message content.
         * reply with 'success' for enqueuing
         */
        vertx.eventBus().localConsumer(Address.redisquesAddress(), (Handler<Message<JsonObject>>) message -> {
            validateMessage(context, message, QueueOperation.lockedEnqueue, "myQueue", "LockRequester");
            message.reply(new JsonObject().put(STATUS, OK));
        });

        HttpRequest request = new HttpRequest(HttpMethod.PUT, "/targetUri", new CaseInsensitiveHeaders(), Buffer.buffer("{\"key\":\"value\"}").getBytes());
        queueClient.lockedEnqueue(request, "myQueue", "LockRequester", event -> async.complete());

        Mockito.verify(monitoringHandler, Mockito.timeout(1000).times(1)).updateEnqueue();
        Mockito.verify(monitoringHandler, Mockito.timeout(1000).times(1)).updateLastUsedQueueSizeInformation(eq("myQueue"));
    }

    @Test
    public void testLockedEnqueueNotUpdatingMonitoringHandlerOnRedisquesFail(TestContext context){
        Async async = context.async();

        /*
         * consume event bus messages directed to redisques and reply with 'failure' for enqueuing
         */
        vertx.eventBus().localConsumer(Address.redisquesAddress(),
                (Handler<Message<JsonObject>>) message -> {
                    validateMessage(context, message, QueueOperation.lockedEnqueue, "myQueue", "LockRequester");
                    message.reply(new JsonObject().put(STATUS, ERROR));
                });

        HttpRequest request = new HttpRequest(HttpMethod.PUT, "/targetUri", new CaseInsensitiveHeaders(), Buffer.buffer("{\"key\":\"value\"}").getBytes());
        queueClient.lockedEnqueue(request, "myQueue", "LockRequester", event -> async.complete());

        // since redisques answered with a 'failure', the monitoringHandler should not be called
        Mockito.verifyZeroInteractions(monitoringHandler);
    }

    private void validateMessage(TestContext context, Message<JsonObject> message, RedisquesAPI.QueueOperation expectedOperation, String queue){
        String opString = message.body().getString(RedisquesAPI.OPERATION);
        context.assertEquals(expectedOperation, RedisquesAPI.QueueOperation.fromString(opString));
        JsonObject payload = message.body().getJsonObject(RedisquesAPI.PAYLOAD);
        context.assertEquals(queue, payload.getString(RedisquesAPI.QUEUENAME));
    }

    private void validateMessage(TestContext context, Message<JsonObject> message, RedisquesAPI.QueueOperation expectedOperation, String queue, boolean unlock){
        String opString = message.body().getString(RedisquesAPI.OPERATION);
        context.assertEquals(expectedOperation, RedisquesAPI.QueueOperation.fromString(opString));
        JsonObject payload = message.body().getJsonObject(RedisquesAPI.PAYLOAD);
        context.assertEquals(queue, payload.getString(RedisquesAPI.QUEUENAME));
        context.assertEquals(unlock, payload.getBoolean(RedisquesAPI.UNLOCK));
    }

    private void validateMessage(TestContext context, Message<JsonObject> message, RedisquesAPI.QueueOperation expectedOperation, String queue, String requestedBy){
        String opString = message.body().getString(RedisquesAPI.OPERATION);
        context.assertEquals(expectedOperation, RedisquesAPI.QueueOperation.fromString(opString));
        JsonObject payload = message.body().getJsonObject(RedisquesAPI.PAYLOAD);
        context.assertEquals(queue, payload.getString(RedisquesAPI.QUEUENAME));
        context.assertEquals(requestedBy, payload.getString(RedisquesAPI.REQUESTED_BY));
        context.assertNotNull(message.body().getString(RedisquesAPI.MESSAGE));
    }
}

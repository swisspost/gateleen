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
    public void testLockedEnqueue(TestContext context){
        Async async = context.async();

        /*
         * consume event bus messages directed to redisques and verify message content.
         * reply with 'success' for enqueuing
         */
        vertx.eventBus().localConsumer(Address.redisquesAddress(), (Handler<Message<JsonObject>>) event -> {
            String opString = event.body().getString(RedisquesAPI.OPERATION);
            RedisquesAPI.QueueOperation operation = RedisquesAPI.QueueOperation.fromString(opString);
            context.assertEquals(RedisquesAPI.QueueOperation.lockedEnqueue, operation);
            JsonObject payload = event.body().getJsonObject(RedisquesAPI.PAYLOAD);
            context.assertEquals("myQueue", payload.getString(RedisquesAPI.QUEUENAME));
            context.assertEquals("LockRequester", payload.getString(RedisquesAPI.REQUESTED_BY));
            context.assertNotNull(event.body().getString(RedisquesAPI.MESSAGE));
            event.reply(new JsonObject().put(STATUS, OK));
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
                (Handler<Message<JsonObject>>) event -> event.reply(new JsonObject().put(STATUS, ERROR)));

        HttpRequest request = new HttpRequest(HttpMethod.PUT, "/targetUri", new CaseInsensitiveHeaders(), Buffer.buffer("{\"key\":\"value\"}").getBytes());
        queueClient.lockedEnqueue(request, "myQueue", "LockRequester", event -> async.complete());

        // since redisques answered with a 'failure', the monitoringHandler should not be called
        Mockito.verifyZeroInteractions(monitoringHandler);
    }
}

package org.swisspush.gateleen.queue.queuing.circuitbreaker.api;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.QueueCircuitBreakerStorage;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.PatternAndCircuitHash;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitState;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.api.QueueCircuitBreakerAPI.*;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.api.QueueCircuitBreakerHttpRequestHandler.HTTP_REQUEST_API_ADDRESS;

/**
 * Tests for the {@link QueueCircuitBreakerHttpRequestHandler} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class QueueCircuitBreakerHttpRequestHandlerTest {

    private Vertx vertx;
    private QueueCircuitBreakerStorage queueCircuitBreakerStorage;

    @org.junit.Rule
    public Timeout rule = Timeout.seconds(5);

    @Before
    public void setUp() {
        vertx = Vertx.vertx();
        queueCircuitBreakerStorage = Mockito.mock(QueueCircuitBreakerStorage.class);
        new QueueCircuitBreakerHttpRequestHandler(vertx, queueCircuitBreakerStorage, "/queuecircuitbreaker/circuit");
    }

    @Test
    public void testGetQueueCircuitStateSuccess(TestContext context) {
        Async async = context.async();

        Mockito.when(queueCircuitBreakerStorage.getQueueCircuitState(anyString()))
                .thenReturn(Future.succeededFuture(QueueCircuitState.HALF_OPEN));

        vertx.eventBus().request(HTTP_REQUEST_API_ADDRESS, QueueCircuitBreakerAPI.buildGetCircuitStatusOperation("someCircuit"),
                (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
                    JsonObject replyBody = reply.result().body();
                    context.assertEquals(OK, replyBody.getString(STATUS));
                    context.assertEquals(QueueCircuitState.HALF_OPEN.name().toLowerCase(), replyBody.getJsonObject(VALUE).getString(STATUS));
                    async.complete();
                });
    }

    @Test
    public void testGetQueueCircuitStateFail(TestContext context) {
        Async async = context.async();

        Mockito.when(queueCircuitBreakerStorage.getQueueCircuitState(anyString()))
                .thenReturn(Future.failedFuture("unable to get state"));

        vertx.eventBus().request(HTTP_REQUEST_API_ADDRESS, QueueCircuitBreakerAPI.buildGetCircuitStatusOperation("someCircuit"),
                (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
                    JsonObject replyBody = reply.result().body();
                    context.assertEquals(ERROR, replyBody.getString(STATUS));
                    async.complete();
                });
    }

    @Test
    public void testGetQueueCircuitInformationSuccess(TestContext context) {
        Async async = context.async();

        JsonObject result = new JsonObject();
        result.put("status", QueueCircuitState.HALF_OPEN.name());
        JsonObject info = new JsonObject();
        info.put("failRatio", 99);
        info.put("circuit", "/path/of/circuit");
        result.put("info", info);

        Mockito.when(queueCircuitBreakerStorage.getQueueCircuitInformation(anyString()))
                .thenReturn(Future.succeededFuture(result));

        vertx.eventBus().request(HTTP_REQUEST_API_ADDRESS, QueueCircuitBreakerAPI.buildGetCircuitInformationOperation("someCircuit"),
                (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
                    JsonObject replyBody = reply.result().body();
                    context.assertEquals(OK, replyBody.getString(STATUS));
                    JsonObject payload = replyBody.getJsonObject(VALUE);
                    context.assertEquals(QueueCircuitState.HALF_OPEN.name(), payload.getString(STATUS));
                    context.assertEquals(99, payload.getJsonObject("info").getInteger("failRatio"));
                    context.assertEquals("/path/of/circuit", payload.getJsonObject("info").getString("circuit"));
                    async.complete();
                });
    }

    @Test
    public void testGetQueueCircuitInformationFail(TestContext context) {
        Async async = context.async();

        Mockito.when(queueCircuitBreakerStorage.getQueueCircuitInformation(anyString()))
                .thenReturn(Future.failedFuture("failed to get information"));

        vertx.eventBus().request(HTTP_REQUEST_API_ADDRESS, QueueCircuitBreakerAPI.buildGetCircuitInformationOperation("someCircuit"),
                (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
                    JsonObject replyBody = reply.result().body();
                    context.assertEquals(ERROR, replyBody.getString(STATUS));
                    async.complete();
                });
    }

    @Test
    public void testCloseCircuitSuccess(TestContext context) {
        Async async = context.async();

        Mockito.when(queueCircuitBreakerStorage.closeCircuit(any(PatternAndCircuitHash.class)))
                .thenReturn(Future.succeededFuture());

        vertx.eventBus().request(HTTP_REQUEST_API_ADDRESS, QueueCircuitBreakerAPI.buildCloseCircuitOperation("someCircuit"),
                (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
                    JsonObject replyBody = reply.result().body();
                    context.assertEquals(OK, replyBody.getString(STATUS));
                    async.complete();
                });
    }

    @Test
    public void testCloseCircuitFail(TestContext context) {
        Async async = context.async();

        Mockito.when(queueCircuitBreakerStorage.closeCircuit(any(PatternAndCircuitHash.class)))
                .thenReturn(Future.failedFuture("unable to close circuit"));

        vertx.eventBus().request(HTTP_REQUEST_API_ADDRESS, QueueCircuitBreakerAPI.buildCloseCircuitOperation("someCircuit"),
                (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
                    JsonObject replyBody = reply.result().body();
                    context.assertEquals(ERROR, replyBody.getString(STATUS));
                    context.assertEquals("unable to close circuit", replyBody.getString(MESSAGE));
                    async.complete();
                });
    }

    @Test
    public void testCloseAllCircuitsSuccess(TestContext context) {
        Async async = context.async();

        Mockito.when(queueCircuitBreakerStorage.closeAllCircuits())
                .thenReturn(Future.succeededFuture());

        vertx.eventBus().request(HTTP_REQUEST_API_ADDRESS, QueueCircuitBreakerAPI.buildCloseAllCircuitsOperation(),
                (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
                    JsonObject replyBody = reply.result().body();
                    context.assertEquals(OK, replyBody.getString(STATUS));
                    async.complete();
                });
    }

    @Test
    public void testCloseAllCircuitsFail(TestContext context) {
        Async async = context.async();

        Mockito.when(queueCircuitBreakerStorage.closeAllCircuits())
                .thenReturn(Future.failedFuture("unable to close all circuits"));

        vertx.eventBus().request(HTTP_REQUEST_API_ADDRESS, QueueCircuitBreakerAPI.buildCloseAllCircuitsOperation(),
                (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
                    JsonObject replyBody = reply.result().body();
                    context.assertEquals(ERROR, replyBody.getString(STATUS));
                    context.assertEquals("unable to close all circuits", replyBody.getString(MESSAGE));
                    async.complete();
                });
    }
}

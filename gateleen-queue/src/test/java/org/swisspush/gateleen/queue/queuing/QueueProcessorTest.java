package org.swisspush.gateleen.queue.queuing;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Matchers;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.QueueCircuitBreaker;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitState;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueResponseType;

import static org.mockito.Mockito.*;

/**
 * Tests for the {@link QueueProcessor} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class QueueProcessorTest {
    private Vertx vertx;
    private HttpClient httpClient;
    private MonitoringHandler monitoringHandler;

    private String PAYLOAD = "{\"method\":\"PUT\",\"uri\":\"/playground/server/tests/exp/item_2\",\"headers\":[],\"payload\":\"eyJrZXkiOiAidmFsdWUifQ==\"}";

    @org.junit.Rule
    public Timeout rule = Timeout.seconds(5);

    @Before
    public void setUp(){
        vertx = Vertx.vertx();
        monitoringHandler = Mockito.mock(MonitoringHandler.class);
        httpClient = Mockito.mock(HttpClient.class);

        Mockito.when(httpClient.request(any(HttpMethod.class), anyString(), Matchers.<Handler<HttpClientResponse>>any())).thenReturn(Mockito.mock(HttpClientRequest.class));

        vertx.eventBus().consumer(Address.redisquesAddress(), event -> {
            event.reply(new JsonObject().put("status", "No such lock"));
        });
    }

    @Test
    public void testQueueProcessorStartStopQueueProcessing(TestContext context){
        QueueProcessor queueProcessor = new QueueProcessor(vertx, httpClient, monitoringHandler);
        context.assertTrue(queueProcessor.isQueueProcessingStarted());

        queueProcessor = new QueueProcessor(vertx, httpClient, monitoringHandler, null, true);
        context.assertTrue(queueProcessor.isQueueProcessingStarted());

        queueProcessor = new QueueProcessor(vertx, httpClient, monitoringHandler, null, false);
        context.assertFalse(queueProcessor.isQueueProcessingStarted());

        queueProcessor.startQueueProcessing();
        context.assertTrue(queueProcessor.isQueueProcessingStarted());
        queueProcessor.stopQueueProcessing();
        context.assertFalse(queueProcessor.isQueueProcessingStarted());
    }

    /**
     * Open circuits should not perform the actual queue requests. This tests verifies the following conditions:
     *
     * <ul>
     *     <li>{@link HttpClient#request(HttpMethod, String, Handler)} is never called</li>
     *     <li>{@link QueueCircuitBreaker#isCircuitCheckEnabled()} is called exactly once to check whether QueueCircuitBreaker is active</li>
     *     <li>{@link QueueCircuitBreaker#handleQueuedRequest(String, HttpRequest)} is called exactly once</li>
     * </ul>
     * @param context the test context
     */
    @Test
    public void testOpenCircuit(TestContext context){
        Async async = context.async();
        QueueCircuitBreaker circuitBreaker = Mockito.spy(new ConfigurableQueueCircuitBreaker(QueueCircuitState.OPEN, true, true));
        new QueueProcessor(vertx, httpClient, monitoringHandler, circuitBreaker);

        vertx.eventBus().send(Address.queueProcessorAddress(), buildQueueEventBusMessage("my_queue"), event -> {
            context.assertTrue(event.succeeded());
            JsonObject result = (JsonObject) event.result().body();
            context.assertEquals("error", result.getString("status"));
            context.assertTrue(result.getString("message").contains("Circuit for queue my_queue is OPEN"));

            // open circuits should not result in actual http requests going out
            verify(httpClient, never()).request(any(HttpMethod.class), anyString(), Matchers.<Handler<HttpClientResponse>>any());
            verify(circuitBreaker, times(1)).isCircuitCheckEnabled();
            verify(circuitBreaker, times(1)).handleQueuedRequest(anyString(), any(HttpRequest.class));

            async.complete();
        });
    }

    /**
     * Not active QueueCircuitBreakers should not execute any tasks. Therefore, the actual queue requests must always be made.
     * <p>This tests verifies the following conditions:</p>
     *
     * <ul>
     *     <li>{@link QueueCircuitBreaker#isCircuitCheckEnabled()} is called exactly once to check whether QueueCircuitBreaker is active</li>
     *     <li>{@link QueueCircuitBreaker#handleQueuedRequest(String, HttpRequest)} is never called</li>
     *     <li>{@link HttpClient#request(HttpMethod, String, Handler)} is called exactly once</li>
     * </ul>
     * @param context the test context
     */
    @Test
    public void testInactiveQueueCircuitBreaker(TestContext context){
        Async async = context.async();
        QueueCircuitBreaker circuitBreaker = Mockito.spy(new ConfigurableQueueCircuitBreaker(QueueCircuitState.OPEN, false, true));
        new QueueProcessor(vertx, httpClient, monitoringHandler, circuitBreaker);

        vertx.eventBus().send(Address.queueProcessorAddress(), buildQueueEventBusMessage("my_queue"), new DeliveryOptions().setSendTimeout(1000), event -> {
            verify(circuitBreaker, times(1)).isCircuitCheckEnabled();
            verify(circuitBreaker, never()).handleQueuedRequest(anyString(), any(HttpRequest.class));
            verify(httpClient, times(1)).request(any(HttpMethod.class), anyString(), Matchers.<Handler<HttpClientResponse>>any());
            async.complete();
        });
    }

    private JsonObject buildQueueEventBusMessage(String queueName){
        JsonObject message = new JsonObject();
        message.put("queue", queueName);
        message.put("payload", PAYLOAD);
        return message;
    }

    class ConfigurableQueueCircuitBreaker implements QueueCircuitBreaker{

        private QueueCircuitState state;
        private boolean circuitCheckEnabled;
        private boolean statisticsUpdateEnabled;

        public ConfigurableQueueCircuitBreaker(QueueCircuitState state, boolean circuitCheckEnabled, boolean statisticsUpdateEnabled){
            this.state = state;
            this.circuitCheckEnabled = circuitCheckEnabled;
            this.statisticsUpdateEnabled = statisticsUpdateEnabled;
        }

        @Override
        public boolean isCircuitCheckEnabled() { return circuitCheckEnabled; }

        @Override
        public boolean isStatisticsUpdateEnabled() { return statisticsUpdateEnabled; }

        @Override
        public Future<QueueCircuitState> handleQueuedRequest(String queueName, HttpRequest queuedRequest) {
            return Future.succeededFuture(state);
        }

        @Override
        public Future<Void> updateStatistics(String queueName, HttpRequest queuedRequest, QueueResponseType queueResponseType) {
            return null;
        }

        @Override
        public Future<Void> lockQueue(String queueName, HttpRequest queuedRequest) { return null; }

        @Override
        public Future<String> unlockQueue(String queueName) { return null; }

        @Override
        public Future<String> unlockNextQueue() { return null; }

        @Override
        public Future<Void> closeCircuit(HttpRequest queuedRequest) {
            return null;
        }

        @Override
        public Future<Void> closeAllCircuits() {
            return null;
        }

        @Override
        public Future<Void> reOpenCircuit(HttpRequest queuedRequest) {
            return null;
        }

        @Override
        public Future<Long> setOpenCircuitsToHalfOpen() { return null; }

        @Override
        public Future<Long> unlockSampleQueues() { return null; }
    }
}

package org.swisspush.gateleen.monitoring;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.redis.client.impl.RedisClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.util.Address;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.awaitility.Awaitility.await;
import static org.awaitility.Durations.TWO_SECONDS;
import static org.swisspush.redisques.util.RedisquesAPI.OK;
import static org.swisspush.redisques.util.RedisquesAPI.STATUS;
import static org.swisspush.redisques.util.RedisquesAPI.VALUE;


/**
 * Tests for the {@link MonitoringHandler} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class MonitoringHandlerTest {

    private Vertx vertx;
    private RedisClient redisClient;
    private MockResourceStorage storage;

    private final String PREFIX = "gateleen.";
    private final String PROPERTY_NAME = "some_property_name";
    private final String REQUEST_PER_RULE_MONITORING_PATH = "/playground/server/monitoring/rpr";

    @Before
    public void setUp(){
        vertx = Vertx.vertx();
        redisClient = Mockito.mock(RedisClient.class);
        storage = new MockResourceStorage();

        // remove system properties to prevent side effects in tests
        System.clearProperty(MonitoringHandler.REQUEST_PER_RULE_PROPERTY);
        System.clearProperty(MonitoringHandler.REQUEST_PER_RULE_SAMPLING_PROPERTY);
        System.clearProperty(MonitoringHandler.REQUEST_PER_RULE_EXPIRY_PROPERTY);
    }

    @After
    public void tearDown(TestContext testContext){
        // close vertx to stop any periodic timers (e.g. the queue size / buffered metrics flush timer) so
        // that they don't leak into and interfere with subsequent tests
        vertx.close(testContext.asyncAssertSuccess());
    }

    @Test
    public void testActiveRequestPerRuleMonitoring(TestContext testContext){
        activateRequestPerRuleMonitoring(false);
        MonitoringHandler mh = new MonitoringHandler(vertx, storage, PREFIX);
        testContext.assertFalse(mh.isRequestPerRuleMonitoringActive(),
                "request per rule monitoring should not be active since no system property was set");

        // set system property and check again
        activateRequestPerRuleMonitoring(true);
        MonitoringHandler mh2 = new MonitoringHandler(vertx, storage, PREFIX);
        testContext.assertTrue(mh2.isRequestPerRuleMonitoringActive(),
                "request per rule monitoring should be active since the correct system property was set");
    }

    @Test
    public void testInitRequestPerRuleMonitoringPath(TestContext testContext){
        MonitoringHandler mh = new MonitoringHandler(vertx, storage, PREFIX);
        testContext.assertNull(mh.getRequestPerRuleMonitoringPath());

        mh = new MonitoringHandler(vertx, storage, PREFIX, "/gateleen/monitoring/rpr/");
        testContext.assertNotNull(mh.getRequestPerRuleMonitoringPath());
        testContext.assertFalse(mh.getRequestPerRuleMonitoringPath().endsWith("/"));

        mh = new MonitoringHandler(vertx, storage, PREFIX, "/gateleen/monitoring/rpr");
        testContext.assertNotNull(mh.getRequestPerRuleMonitoringPath());
        testContext.assertFalse(mh.getRequestPerRuleMonitoringPath().endsWith("/"));
    }

    @Test
    public void testInitSamplingAndExpiry(TestContext testContext){
        activateRequestPerRuleMonitoring(true);
        MonitoringHandler mh = new MonitoringHandler(vertx, storage, PREFIX);
        testContext.assertEquals(MonitoringHandler.REQUEST_PER_RULE_DEFAULT_SAMPLING, mh.getRequestPerRuleSampling());
        testContext.assertEquals(MonitoringHandler.REQUEST_PER_RULE_DEFAULT_EXPIRY, mh.getRequestPerRuleExpiry());

        // change sampling and expiry through system property
        System.setProperty(MonitoringHandler.REQUEST_PER_RULE_SAMPLING_PROPERTY, "10000");
        System.setProperty(MonitoringHandler.REQUEST_PER_RULE_EXPIRY_PROPERTY, "120");
        mh = new MonitoringHandler(vertx, storage, PREFIX);

        testContext.assertEquals(10000L, mh.getRequestPerRuleSampling());
        testContext.assertEquals(120L, mh.getRequestPerRuleExpiry());
    }

    @Test
    public void testExternalReceiver(TestContext testContext){
        Async async = testContext.async();

        activateRequestPerRuleMonitoring(true);
        System.setProperty(MonitoringHandler.REQUEST_PER_RULE_SAMPLING_PROPERTY, "100");
        MonitoringHandler mh = new MonitoringHandler(vertx, storage, PREFIX, "/rule/path");

        PUTRequest request = new PUTRequest();
        request.addHeader(PROPERTY_NAME, "my_value_123");

        mh.registerReceiver(event -> {
            final JsonObject body = event.body();
            testContext.assertEquals("gateleen.rpr.my_value_123.a_fancy_rule", body.getString("name"));
            async.complete();
        });

        mh.updateRequestPerRuleMonitoring(request, "a_fancy_rule");
    }

    @Test
    public void testWriteRequestPerRuleMonitoringToStorage(){

        activateRequestPerRuleMonitoring(true);
        System.setProperty(MonitoringHandler.REQUEST_PER_RULE_SAMPLING_PROPERTY, "100");
        MonitoringHandler mh = new MonitoringHandler(vertx, storage, PREFIX, REQUEST_PER_RULE_MONITORING_PATH);

        PUTRequest request = new PUTRequest();
        request.addHeader(PROPERTY_NAME, "my_value_123");
        mh.updateRequestPerRuleMonitoring(request, "a_fancy_rule");

        await().atMost(TWO_SECONDS).until(storageContainsData("my_value_123.a_fancy_rule"));
    }

    private Callable<Boolean> storageContainsData(String valueToLookFor) {
        return () -> {
            boolean dataFound = false;
            for (Map.Entry<String, String> entry : storage.getMockData().entrySet()) {
                if(entry.getKey().contains(valueToLookFor)){
                    dataFound = true;
                    break;
                }
            }
            return dataFound;
        };
    }

    static class PUTRequest extends DummyHttpServerRequest {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();

        @Override public HttpMethod method() {
            return HttpMethod.PUT;
        }
        @Override public String uri() {
            return "/playground/server/some_resource";
        }
        @Override public MultiMap headers() { return headers; }

        @Override
        public String getHeader(String headerName) { return headers.get(headerName); }

        public void addHeader(String headerName, String headerValue){ headers.add(headerName, headerValue); }
    }

    private void activateRequestPerRuleMonitoring(boolean activate){
        if(activate){
            System.setProperty(MonitoringHandler.REQUEST_PER_RULE_PROPERTY, PROPERTY_NAME);
        } else {
            System.clearProperty(MonitoringHandler.REQUEST_PER_RULE_PROPERTY);
        }
    }

    @Test
    public void testPendingRequestCountIsBufferedAndFlushedOnlyOnChange(TestContext testContext) throws InterruptedException {
        MonitoringHandler mh = new MonitoringHandler(vertx, storage, PREFIX);

        AtomicInteger receivedMessages = new AtomicInteger(0);
        AtomicReference<JsonObject> lastMessage = new AtomicReference<>();
        mh.registerReceiver(message -> {
            JsonObject body = message.body();
            if ((PREFIX + MonitoringHandler.PENDING_REQUESTS_METRIC).equals(body.getString("name"))) {
                receivedMessages.incrementAndGet();
                lastMessage.set(body);
            }
        });

        // starting/stopping requests must not send anything to the eventbus directly, values are buffered locally
        long t1 = mh.startRequestMetricTracking(null, "/some/uri");
        mh.startRequestMetricTracking(null, "/some/uri");
        mh.startRequestMetricTracking(null, "/some/uri");
        mh.stopRequestMetricTracking(null, t1, "/some/uri");
        testContext.assertEquals(0, receivedMessages.get(),
                "no eventbus message should have been sent before the buffered metrics are flushed");

        // flushing should send exactly one message with the accumulated value (3 starts - 1 stop = 2)
        mh.flushBufferedMetrics();
        await().atMost(TWO_SECONDS).until(() -> receivedMessages.get() == 1);
        testContext.assertEquals(2L, lastMessage.get().getLong("n"));

        // flushing again without any change in between must not send another message
        mh.flushBufferedMetrics();
        Thread.sleep(200);
        testContext.assertEquals(1, receivedMessages.get());

        // a further change should be picked up and sent again on the next flush
        mh.startRequestMetricTracking(null, "/some/uri");
        mh.flushBufferedMetrics();
        await().atMost(TWO_SECONDS).until(() -> receivedMessages.get() == 2);
        testContext.assertEquals(3L, lastMessage.get().getLong("n"));
    }

    @Test
    public void testLastUsedQueueSizeIsBufferedAndFlushedOnlyOnChange(TestContext testContext) throws InterruptedException {
        MonitoringHandler mh = new MonitoringHandler(vertx, storage, PREFIX);

        AtomicInteger queueSizeReplyValue = new AtomicInteger(5);
        vertx.eventBus().consumer(Address.redisquesAddress(), (Handler<Message<JsonObject>>) message ->
                message.reply(new JsonObject().put(STATUS, OK).put(VALUE, queueSizeReplyValue.get())));

        AtomicInteger receivedMessages = new AtomicInteger(0);
        AtomicReference<JsonObject> lastMessage = new AtomicReference<>();
        mh.registerReceiver(message -> {
            JsonObject body = message.body();
            if ((PREFIX + MonitoringHandler.LAST_USED_QUEUE_SIZE_METRIC).equals(body.getString("name"))) {
                receivedMessages.incrementAndGet();
                lastMessage.set(body);
            }
        });

        mh.updateLastUsedQueueSizeInformation("myQueue");
        testContext.assertEquals(0, receivedMessages.get(),
                "no eventbus message should have been sent before the buffered metrics are flushed");

        mh.flushBufferedMetrics();
        await().atMost(TWO_SECONDS).until(() -> receivedMessages.get() == 1);
        testContext.assertEquals(5L, lastMessage.get().getLong("n"));

        // flushing again without a new update in between must not trigger another redisques roundtrip / message
        mh.flushBufferedMetrics();
        Thread.sleep(200);
        testContext.assertEquals(1, receivedMessages.get());

        // an update with the same underlying queue size should still not produce a new message once flushed
        mh.updateLastUsedQueueSizeInformation("myQueue");
        mh.flushBufferedMetrics();
        Thread.sleep(200);
        testContext.assertEquals(1, receivedMessages.get());

        // a changed queue size should be sent again
        queueSizeReplyValue.set(9);
        mh.updateLastUsedQueueSizeInformation("myQueue");
        mh.flushBufferedMetrics();
        await().atMost(TWO_SECONDS).until(() -> receivedMessages.get() == 2);
        testContext.assertEquals(9L, lastMessage.get().getLong("n"));
    }

    @Test
    public void testListenerCountIsBufferedAndFlushedOnlyOnChange(TestContext testContext) throws InterruptedException {
        MonitoringHandler mh = new MonitoringHandler(vertx, storage, PREFIX);

        AtomicInteger receivedMessages = new AtomicInteger(0);
        AtomicReference<JsonObject> lastMessage = new AtomicReference<>();
        mh.registerReceiver(message -> {
            JsonObject body = message.body();
            if ((PREFIX + MonitoringHandler.LISTENER_COUNT_METRIC).equals(body.getString("name"))) {
                receivedMessages.incrementAndGet();
                lastMessage.set(body);
            }
        });

        mh.updateListenerCount(3);
        testContext.assertEquals(0, receivedMessages.get(),
                "no eventbus message should have been sent before the buffered metrics are flushed");

        mh.flushBufferedMetrics();
        await().atMost(TWO_SECONDS).until(() -> receivedMessages.get() == 1);
        testContext.assertEquals(3L, lastMessage.get().getLong("n"));

        // unchanged value must not be re-sent
        mh.updateListenerCount(3);
        mh.flushBufferedMetrics();
        Thread.sleep(200);
        testContext.assertEquals(1, receivedMessages.get());

        // changed value must be sent again
        mh.updateListenerCount(7);
        mh.flushBufferedMetrics();
        await().atMost(TWO_SECONDS).until(() -> receivedMessages.get() == 2);
        testContext.assertEquals(7L, lastMessage.get().getLong("n"));
    }

    @Test
    public void testRouteCountIsBufferedAndFlushedOnlyOnChange(TestContext testContext) throws InterruptedException {
        MonitoringHandler mh = new MonitoringHandler(vertx, storage, PREFIX);

        AtomicInteger receivedMessages = new AtomicInteger(0);
        AtomicReference<JsonObject> lastMessage = new AtomicReference<>();
        mh.registerReceiver(message -> {
            JsonObject body = message.body();
            if ((PREFIX + MonitoringHandler.ROUTE_COUNT_METRIC).equals(body.getString("name"))) {
                receivedMessages.incrementAndGet();
                lastMessage.set(body);
            }
        });

        mh.updateRoutesCount(4);
        testContext.assertEquals(0, receivedMessages.get(),
                "no eventbus message should have been sent before the buffered metrics are flushed");

        mh.flushBufferedMetrics();
        await().atMost(TWO_SECONDS).until(() -> receivedMessages.get() == 1);
        testContext.assertEquals(4L, lastMessage.get().getLong("n"));

        // unchanged value must not be re-sent
        mh.updateRoutesCount(4);
        mh.flushBufferedMetrics();
        Thread.sleep(200);
        testContext.assertEquals(1, receivedMessages.get());

        // changed value must be sent again
        mh.updateRoutesCount(8);
        mh.flushBufferedMetrics();
        await().atMost(TWO_SECONDS).until(() -> receivedMessages.get() == 2);
        testContext.assertEquals(8L, lastMessage.get().getLong("n"));
    }

    @Test
    public void testMergeMetricsIntoSingleMessageDisabledByDefault(TestContext testContext){
        MonitoringHandler mh = new MonitoringHandler(vertx, storage, PREFIX);
        testContext.assertFalse(mh.isMergeMetricsIntoSingleMessage(),
                "merging of metrics into a single message should be disabled by default");
    }

    @Test
    public void testMergeMetricsIntoSingleMessageDisabledByDefaultWithRequestPerRulePath(TestContext testContext){
        MonitoringHandler mh = new MonitoringHandler(vertx, storage, PREFIX, "/gateleen/monitoring/rpr");
        testContext.assertFalse(mh.isMergeMetricsIntoSingleMessage(),
                "merging of metrics into a single message should be disabled by default also when using the " +
                        "constructor accepting a requestPerRulePath but no explicit mergeMetricsIntoSingleMessage flag");
    }

    @Test
    public void testMergeMetricsIntoSingleMessageCanBeExplicitlyDisabled(TestContext testContext){
        MonitoringHandler mh = new MonitoringHandler(vertx, storage, PREFIX, null, false);
        testContext.assertFalse(mh.isMergeMetricsIntoSingleMessage());
    }

    @Test
    public void testMergeMetricsIntoSingleMessage(TestContext testContext) throws InterruptedException {
        MonitoringHandler mh = new MonitoringHandler(vertx, storage, PREFIX, null, true);
        testContext.assertTrue(mh.isMergeMetricsIntoSingleMessage());

        AtomicInteger receivedBatchMessages = new AtomicInteger(0);
        AtomicInteger receivedSingleMessages = new AtomicInteger(0);
        AtomicReference<JsonObject> lastBatch = new AtomicReference<>();
        mh.registerReceiver((Handler<Message<JsonObject>>) message -> {
            JsonObject body = message.body();
            if (MonitoringHandler.BATCH.equals(body.getString(MonitoringHandler.METRIC_ACTION))) {
                receivedBatchMessages.incrementAndGet();
                lastBatch.set(body);
            } else if (body.getString("name") != null
                    && (body.getString("name").equals(PREFIX + MonitoringHandler.PENDING_REQUESTS_METRIC)
                    || body.getString("name").equals(PREFIX + MonitoringHandler.LISTENER_COUNT_METRIC)
                    || body.getString("name").equals(PREFIX + MonitoringHandler.ROUTE_COUNT_METRIC))) {
                receivedSingleMessages.incrementAndGet();
            }
        });

        // change 2 of the 3 synchronous, buffered metrics in one go
        mh.startRequestMetricTracking(null, "/some/uri");
        mh.updateListenerCount(5);

        mh.flushBufferedMetrics();
        await().atMost(TWO_SECONDS).until(() -> receivedBatchMessages.get() == 1);
        testContext.assertEquals(0, receivedSingleMessages.get(),
                "no individual metric messages should be sent while merging is enabled");

        JsonArray items = lastBatch.get().getJsonArray(MonitoringHandler.METRICS_BATCH_ITEMS);
        testContext.assertEquals(2, items.size());

        boolean foundPending = false;
        boolean foundListener = false;
        for (int i = 0; i < items.size(); i++) {
            JsonObject item = items.getJsonObject(i);
            if ((PREFIX + MonitoringHandler.PENDING_REQUESTS_METRIC).equals(item.getString("name"))) {
                foundPending = true;
                testContext.assertEquals(1L, item.getLong("n"));
            } else if ((PREFIX + MonitoringHandler.LISTENER_COUNT_METRIC).equals(item.getString("name"))) {
                foundListener = true;
                testContext.assertEquals(5L, item.getLong("n"));
            }
        }
        testContext.assertTrue(foundPending, "batch should contain the pending request count metric");
        testContext.assertTrue(foundListener, "batch should contain the listener count metric");

        // flushing again without any change must not send another batch message
        mh.flushBufferedMetrics();
        Thread.sleep(200);
        testContext.assertEquals(1, receivedBatchMessages.get());
    }

    @Test
    public void testMergeMetricsIntoSingleMessageIncludesLastUsedQueueSize(TestContext testContext){
        MonitoringHandler mh = new MonitoringHandler(vertx, storage, PREFIX, null, true);

        vertx.eventBus().consumer(Address.redisquesAddress(), (Handler<Message<JsonObject>>) message ->
                message.reply(new JsonObject().put(STATUS, OK).put(VALUE, 5)));

        AtomicInteger receivedBatchMessages = new AtomicInteger(0);
        AtomicReference<JsonObject> lastBatch = new AtomicReference<>();
        mh.registerReceiver(message -> {
            JsonObject body = message.body();
            if (MonitoringHandler.BATCH.equals(body.getString(MonitoringHandler.METRIC_ACTION))) {
                receivedBatchMessages.incrementAndGet();
                lastBatch.set(body);
            }
        });

        // trigger both a synchronous (listener count) and an asynchronous (last used queue size) metric change
        // within the same flush cycle; both must end up merged into the same single batch message
        mh.updateListenerCount(3);
        mh.updateLastUsedQueueSizeInformation("myQueue");

        mh.flushBufferedMetrics();
        await().atMost(TWO_SECONDS).until(() -> receivedBatchMessages.get() == 1);

        JsonArray items = lastBatch.get().getJsonArray(MonitoringHandler.METRICS_BATCH_ITEMS);
        testContext.assertEquals(2, items.size());

        boolean foundListener = false;
        boolean foundQueueSize = false;
        for (int i = 0; i < items.size(); i++) {
            JsonObject item = items.getJsonObject(i);
            if ((PREFIX + MonitoringHandler.LISTENER_COUNT_METRIC).equals(item.getString("name"))) {
                foundListener = true;
                testContext.assertEquals(3L, item.getLong("n"));
            } else if ((PREFIX + MonitoringHandler.LAST_USED_QUEUE_SIZE_METRIC).equals(item.getString("name"))) {
                foundQueueSize = true;
                testContext.assertEquals(5L, item.getLong("n"));
            }
        }
        testContext.assertTrue(foundListener, "batch should contain the listener count metric");
        testContext.assertTrue(foundQueueSize, "batch should contain the last used queue size metric");

        // only one batch message must have been sent, not two separate ones
        testContext.assertEquals(1, receivedBatchMessages.get());
    }
}



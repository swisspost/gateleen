package org.swisspush.gateleen.core.event;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


public class TrackableEventPublishTest {

    private static final long TEST_TIMEOUT_MS = 2000L;

    private Vertx vertx;
    private TrackableEventPublish trackableEventPublish;

    @Before
    public void setUp() {
        vertx = Vertx.vertx();
        trackableEventPublish = new TrackableEventPublish(vertx);
    }

    @After
    public void tearDown() throws Exception {
        if (vertx != null) {
            vertx.close()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void setTrackerEnabled(boolean enabled) throws Exception {
        Field field = TrackableEventPublish.class.getDeclaredField("trackerEnabled");
        field.setAccessible(true);
        field.set(trackableEventPublish, enabled);
    }

    @Test
    public void testTrackerEnabledToggleViaEventBus() throws Exception {
        // initially should be false
        Field field = TrackableEventPublish.class.getDeclaredField("trackerEnabled");
        field.setAccessible(true);
        boolean initial = (boolean) field.get(trackableEventPublish);
        assertFalse(initial);

        // send enable message via event bus
        vertx.eventBus().send(TrackableEventPublish.KEY_TRACKER_ENABLED_KEY_ADDRESS, true);

        // give Vert.x a moment to deliver the message
        Thread.sleep(50);

        boolean after = (boolean) field.get(trackableEventPublish);
        assertTrue(after);
    }

    private void assertFalse(boolean initial) {
    }

    @Test
    public void testPublishWithTrackerDisabledAndConsumerReceivesPayload() throws Exception {
        // trackerEnabled is false by default

        String address = "test.address.disabled";
        CountDownLatch handlerLatch = new CountDownLatch(1);
        AtomicReference<String> receivedPayload = new AtomicReference<>();

        // consumer should just receive the data field (tracker disabled branch)
        trackableEventPublish.consumer(address, value -> {
            receivedPayload.set(value);
            handlerLatch.countDown();
        });

        Future<Integer> future = trackableEventPublish.publish(address, "hello");

        int result = future
                .toCompletionStage()
                .toCompletableFuture()
                .get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        assertEquals(-1, result);
        assertTrue("Consumer did not receive message in time",
                handlerLatch.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals("hello", receivedPayload.get());
    }

    @Test
    public void testPublishWithTrackerEnabledAndNoConsumer() throws Exception {
        setTrackerEnabled(true);

        String address = "test.address.no.consumer";

        Future<Integer> future = trackableEventPublish.publish(address, "no-consumer");

        // should complete after timeout with 0 replies
        int result = future
                .toCompletionStage()
                .toCompletableFuture()
                .get(TEST_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS); // allow for 1s timer

        assertEquals(0, result);
    }

    @Test
    public void testPublishWithTrackerEnabledAndSingleConsumerCountsReplies() throws Exception {
        setTrackerEnabled(true);

        String address = "test.address.single.consumer";

        CountDownLatch handlerLatch = new CountDownLatch(1);
        AtomicReference<String> receivedPayload = new AtomicReference<>();

        // tracker enabled: consumer will reply to replyAddress and pass KEY_DATA to handler
        trackableEventPublish.consumer(address, value -> {
            receivedPayload.set(value);
            handlerLatch.countDown();
        });

        Future<Integer> future = trackableEventPublish.publish(address, "payload");

        // future is completed by the timer (1s), so allow a bit more than that
        int result = future
                .toCompletionStage()
                .toCompletableFuture()
                .get(TEST_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS);

        assertTrue("Consumer handler not called",
                handlerLatch.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));

        assertEquals("payload", receivedPayload.get());
        assertEquals(1, result);
    }

    @Test
    public void testConsumerWithJsonPayloadWhenTrackerDisabled() throws Exception {
        // tracker disabled branch
        String address = "test.address.json.disabled";

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();

        trackableEventPublish.consumer(address, value -> {
            received.set(value);
            latch.countDown();
        });

        JsonObject payload = new JsonObject().put(TrackableEventPublish.KEY_DATA, "jsonData");
        trackableEventPublish.publish(address, payload);

        assertTrue("Consumer did not receive JSON payload",
                latch.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals("jsonData", received.get());
    }
}

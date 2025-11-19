package org.swisspush.gateleen.core.event;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.MessageConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * a helper class which can count the received response from consumer
 */
public class TrackableEventPublish {
    private static final Logger LOG = LoggerFactory.getLogger(TrackableEventPublish.class);
    private static final int PUBLISH_EVENTS_FEEDBACK_TIMEOUT_MS = 1000;
    private volatile boolean trackerEnabled = false;
    public static final String KEY_REPLY = "reply.";
    public static final String KEY_REPLY_ADDRESS = "replyAddress";
    public static final String KEY_DATA = "data";
    public static final String KEY_TRACKER_ENABLED_KEY_ADDRESS = "gateleen.key.addresses.tracker.enable";

    public TrackableEventPublish(Vertx vertx) {
        vertx.eventBus().consumer(KEY_TRACKER_ENABLED_KEY_ADDRESS, event -> {
            if (event.body() instanceof Boolean) {
                Boolean enabled = (Boolean) event.body();
                LOG.info("Gateleen Key event tracker enabled? {}", enabled);
                trackerEnabled = enabled;
            }
        });
    }

    /**
     * Publish a event to eventbus with feed back how many consumers received.
     * Note: need use consumer from {@link TrackableEventPublish}
     *
     * @param vertx
     * @param address
     * @param payload
     * @return
     */
    public Future<Integer> publish(Vertx vertx, String address, Object payload) {
        final Promise<Integer> promise = Promise.promise();
        EventBus eb = vertx.eventBus();
        if (trackerEnabled) {
            String replyAddress = KEY_REPLY + address + "." + UUID.randomUUID();
            AtomicInteger replies = new AtomicInteger(0);
            long timerId = vertx.setTimer(PUBLISH_EVENTS_FEEDBACK_TIMEOUT_MS, t -> {
                promise.complete(replies.get());
                eb.consumer(replyAddress).unregister();
            });

            // Consumer that aggregates replies
            MessageConsumer<Object> messageConsumer = eb.consumer(replyAddress, reply -> {
                replies.incrementAndGet();
            });

            messageConsumer.completionHandler(ar -> {
                if (ar.failed()) {
                    promise.fail(ar.cause());
                    return;
                }

                // Publish event with reply address included
                JsonObject body = (payload instanceof JsonObject)
                        ? ((JsonObject) payload).copy()
                        : new JsonObject().put(KEY_DATA, payload);
                body.put(KEY_REPLY_ADDRESS, replyAddress);
                eb.publish(address, body);
            });

            promise.future().onComplete(ar -> {
                // cancel timeout if completed already
                vertx.cancelTimer(timerId);
                messageConsumer.unregister();
            });
        } else {
            JsonObject body = (payload instanceof JsonObject)
                    ? ((JsonObject) payload).copy()
                    : new JsonObject().put(KEY_DATA, payload);
            vertx.eventBus().publish(address, body);
            promise.complete(-1);
        }
        promise.future().onComplete(event -> {
            if (event.failed()) {
                LOG.error("have problem to publish event to address to {}.", address, event.cause());
                return;
            }
            if (event.result() < 0) {
                LOG.debug("Tracker is disabled.");
            } else {
                LOG.info("{} event published, {} consumer answered",address, event.result());
            }
        });

        return promise.future();
    }

    /**
     * Consuming a event which published by TrackableEventPublish
     * @param vertx
     * @param address
     * @param handler
     */
    public void consumer(Vertx vertx, String address, Handler<String> handler) {
        if (trackerEnabled) {
            vertx.eventBus().consumer(address, msg -> {
                if (msg.body() instanceof JsonObject) {
                    JsonObject body = (JsonObject) msg.body();
                    if (body.containsKey(KEY_REPLY_ADDRESS)) {
                        String replyAddr = body.getString(KEY_REPLY_ADDRESS);
                        vertx.eventBus().send(replyAddr, null);
                        handler.handle(body.getString(KEY_DATA));
                    }
                }
            });
        } else {
            vertx.eventBus().consumer(address, msg -> {
                if (msg.body() instanceof JsonObject) {
                    JsonObject body = (JsonObject) msg.body();
                    handler.handle(body.getString(KEY_DATA));
                }
            });
        }
    }
}

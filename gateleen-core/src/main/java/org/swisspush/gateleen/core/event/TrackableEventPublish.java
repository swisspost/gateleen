package org.swisspush.gateleen.core.event;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.MessageConsumer;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * a helper class which can count the received response from consumer
 */
public class TrackableEventPublish {

    public static final String KEY_REPLY = "reply.";
    public static final String KEY_REPLY_ADDRESS = "replyAddress";
    public static final String KEY_DATA = "data";


    /**
     * Publish a event to eventbus with feed back how many consumers received.
     * Note: need use consumer from {@link TrackableEventPublish}
     *
     * @param vertx
     * @param address
     * @param payload
     * @param timeoutMs
     * @return
     */
    public static Future<Integer> publish(Vertx vertx, String address, Object payload, long timeoutMs) {
        Promise<Integer> promise = Promise.promise();
        EventBus eb = vertx.eventBus();

        String replyAddress = KEY_REPLY + address + "." + UUID.randomUUID();
        AtomicInteger replies = new AtomicInteger(0);
        long timerId = vertx.setTimer(timeoutMs, t -> {
            promise.tryComplete(replies.get());
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

        return promise.future();
    }

    /**
     * Consuming a event which published by TrackableEventPublish
     * @param vertx
     * @param address
     * @param handler
     */
    public static void consumer(Vertx vertx, String address, Handler<String> handler) {
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
    }
}

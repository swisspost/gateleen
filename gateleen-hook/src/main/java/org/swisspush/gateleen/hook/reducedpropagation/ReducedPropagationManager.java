package org.swisspush.gateleen.hook.reducedpropagation;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by webermarca on 23.02.2017.
 */
public class ReducedPropagationManager {

    private Vertx vertx;
    private final ReducedPropagationStorage storage;
    private final String redisquesAddress;

    private Logger log = LoggerFactory.getLogger(ReducedPropagationManager.class);

    public ReducedPropagationManager(Vertx vertx, ReducedPropagationStorage storage, String redisquesAddress) {
        this.vertx = vertx;
        this.storage = storage;
        this.redisquesAddress = redisquesAddress;
    }

    public Future<Void> addQueueTimer(String queue, long propagationInterval) {
        Future<Void> future = Future.future();
        long expireTS = System.currentTimeMillis() + propagationInterval;
        storage.addQueue(queue, expireTS).setHandler(event -> {
            if (event.failed()) {
                log.error("addQueueTimer for queue '" + queue + "' and propagationInvertval '" + propagationInterval + "' failed. Cause: " + event.cause());
                future.fail(event.cause());
                return;
            }
            if (event.result()) {
                log.info("Timer for queue '" + queue + "' and with expiration at '" + expireTS + "' started.");
            } else {
                log.info("Timer for queue '" + queue + "' already running.");
            }
            future.complete();
        });
        return future;
    }
}

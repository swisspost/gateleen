package org.swisspush.gateleen.queue.queuing.splitter;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerRequest;

/**
 * {@inheritDoc}
 */
public class NoOpQueueSplitter implements QueueSplitter {

    @Override
    public Future<Void> initialize() {
        Promise<Void> promise = Promise.promise();
        promise.complete();
        return promise.future();
    }

    @Override
    /**
     * {@inheritDoc}
     */
    public String convertToSubQueue(String queue, HttpServerRequest request) {
        return queue;
    }
}

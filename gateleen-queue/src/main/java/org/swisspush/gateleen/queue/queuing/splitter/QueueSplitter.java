package org.swisspush.gateleen.queue.queuing.splitter;

import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;

/**
 * Interface for queues configured to be split in sub-queues. The method {@link QueueSplitter#convertToSubQueue(String, HttpServerRequest)}
 * evaluates the convert of the queue name in a sub-queue name.
 *
 * @author https://github.com/gcastaldi [Giannandrea Castaldi]
 */
public interface QueueSplitter {

    public Future<Void> initialize();

        /**
         * Convert the queue name in a sub-queue name. If not necessary maintains the initial queue name.
         *
         * @param queue
         * @param request
         * @return sub-queue name
         */
    String convertToSubQueue(String queue, HttpServerRequest request);
}

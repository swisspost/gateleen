package org.swisspush.gateleen.queue.queuing;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import org.swisspush.gateleen.core.http.HttpRequest;

/**
 * @author bovetl
 */
public interface RequestQueue {
    void enqueue(HttpServerRequest request, Buffer buffer, String queue);

    void enqueue(HttpServerRequest request, MultiMap headers, Buffer buffer, String queue);

    void enqueue(HttpRequest request, String queue);

    void enqueue(HttpRequest request, String queue, Handler<Void> doneHandler);

    void lockedEnqueue(HttpRequest request, String queue, String lockRequestedBy, Handler<Void> doneHandler);
}

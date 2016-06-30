package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.Future;
import org.swisspush.gateleen.core.http.HttpRequest;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public interface QueueCircuitBreaker {

    Future<QueueCircuitState> handleQueuedRequest(String queueName, HttpRequest queuedRequest);

    void setActive(boolean active);

    boolean isActive();
}

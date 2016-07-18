package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.Future;
import org.swisspush.gateleen.core.http.HttpRequest;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public interface QueueCircuitBreaker {

    Future<QueueCircuitState> handleQueuedRequest(String queueName, HttpRequest queuedRequest);

    Future<Void> updateStatistics(String queueName, HttpRequest queuedRequest, QueueResponseType queueResponseType);

    boolean isCircuitCheckEnabled();

    boolean isStatisticsUpdateEnabled();

    Future<Void> lockQueue(String queueName, HttpRequest queuedRequest);

    Future<String> unlockQueue(String queueName);

    Future<String> unlockNextQueue();

    Future<Void> closeCircuit(HttpRequest queuedRequest);

    Future<Void> closeAllCircuits();

    Future<Void> reOpenCircuit(HttpRequest queuedRequest);

    Future<Void> setOpenCircuitsToHalfOpen();

    Future<Long> unlockSampleQueues();
}

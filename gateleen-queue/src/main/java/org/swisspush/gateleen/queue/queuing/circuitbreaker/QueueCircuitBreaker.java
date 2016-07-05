package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.Future;
import org.swisspush.gateleen.core.http.HttpRequest;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public interface QueueCircuitBreaker {

    Future<QueueCircuitState> handleQueuedRequest(String queueName, HttpRequest queuedRequest);

    Future<String> updateStatistics(String queueName, HttpRequest queuedRequest, QueueResponseType queueResponseType);

    void enableCircuitCheck(boolean circuitCheckEnabled);

    boolean isCircuitCheckEnabled();

    void enableStatisticsUpdate(boolean statisticsUpdateEnabled);

    boolean isStatisticsUpdateEnabled();
}

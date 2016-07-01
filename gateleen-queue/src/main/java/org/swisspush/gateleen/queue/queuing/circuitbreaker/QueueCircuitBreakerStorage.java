package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.Future;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public interface QueueCircuitBreakerStorage {

    Future<Void> resetAllEndpoints();

    Future<QueueCircuitState> getQueueCircuitState(String endpoint);

//    Future<Void> updateStatistics(String endpointHash, String endpointName, QueueResponseType queueResponseType);
}

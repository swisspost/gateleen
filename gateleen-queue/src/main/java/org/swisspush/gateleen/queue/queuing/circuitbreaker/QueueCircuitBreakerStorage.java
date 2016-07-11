package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.Future;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public interface QueueCircuitBreakerStorage {

    Future<Void> resetAllEndpoints();

    Future<QueueCircuitState> getQueueCircuitState(PatternAndEndpointHash patternAndEndpointHash);

    Future<UpdateStatisticsResult> updateStatistics(PatternAndEndpointHash patternAndEndpointHash, String uniqueRequestID, long timestamp, int errorThresholdPercentage, long entriesMaxAgeMS, long minSampleCount, long maxSampleCount, QueueResponseType queueResponseType);

    Future<Void> lockQueue(String queueName, PatternAndEndpointHash patternAndEndpointHash);

    Future<Void> closeCircuit(PatternAndEndpointHash patternAndEndpointHash);

    Future<Void> reOpenCircuit(PatternAndEndpointHash patternAndEndpointHash);
}

package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.Future;

import java.util.List;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public interface QueueCircuitBreakerStorage {

    Future<QueueCircuitState> getQueueCircuitState(PatternAndCircuitHash patternAndCircuitHash);

    Future<UpdateStatisticsResult> updateStatistics(PatternAndCircuitHash patternAndCircuitHash, String uniqueRequestID, long timestamp, int errorThresholdPercentage, long entriesMaxAgeMS, long minQueueSampleCount, long maxQueueSampleCount, QueueResponseType queueResponseType);

    Future<Void> lockQueue(String queueName, PatternAndCircuitHash patternAndCircuitHash);

    Future<String> popQueueToUnlock();

    Future<Void> closeCircuit(PatternAndCircuitHash patternAndCircuitHash);

    Future<Void> closeAndRemoveCircuit(PatternAndCircuitHash patternAndCircuitHash);

    Future<Void> closeAllCircuits();

    Future<Void> reOpenCircuit(PatternAndCircuitHash patternAndCircuitHash);

    Future<Void> setOpenCircuitsToHalfOpen();

    Future<List<String>> unlockSampleQueues();
}

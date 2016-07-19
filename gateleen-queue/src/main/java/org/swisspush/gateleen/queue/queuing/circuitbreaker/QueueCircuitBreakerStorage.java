package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public interface QueueCircuitBreakerStorage {

    Future<QueueCircuitState> getQueueCircuitState(PatternAndCircuitHash patternAndCircuitHash);

    Future<QueueCircuitState> getQueueCircuitState(String circuitHash);

    Future<JsonObject> getQueueCircuitInformation(String circuitHash);

    Future<JsonObject> getAllCircuits();

    Future<UpdateStatisticsResult> updateStatistics(PatternAndCircuitHash patternAndCircuitHash, String uniqueRequestID, long timestamp, int errorThresholdPercentage, long entriesMaxAgeMS, long minQueueSampleCount, long maxQueueSampleCount, QueueResponseType queueResponseType);

    Future<Void> lockQueue(String queueName, PatternAndCircuitHash patternAndCircuitHash);

    Future<String> popQueueToUnlock();

    Future<Void> closeCircuit(PatternAndCircuitHash patternAndCircuitHash);

    Future<Void> closeAndRemoveCircuit(PatternAndCircuitHash patternAndCircuitHash);

    Future<Void> closeAllCircuits();

    Future<Void> reOpenCircuit(PatternAndCircuitHash patternAndCircuitHash);

    Future<Long> setOpenCircuitsToHalfOpen();

    Future<List<String>> unlockSampleQueues();
}

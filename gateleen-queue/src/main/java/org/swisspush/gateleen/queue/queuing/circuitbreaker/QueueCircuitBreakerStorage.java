package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Response;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.PatternAndCircuitHash;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitState;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueResponseType;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.UpdateStatisticsResult;

/**
 * Provides storage access for the {@link QueueCircuitBreaker}.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public interface QueueCircuitBreakerStorage {

    /**
     * Get the actual {@link QueueCircuitState} of the provided patternAndCircuitHash object. When no circuit could be
     * found in the storage, {@link QueueCircuitState#CLOSED} is returned.
     *
     * @param patternAndCircuitHash circuit information
     * @return returns the actual {@link QueueCircuitState} or {@link QueueCircuitState#CLOSED} when no circuit was found
     */
    Future<QueueCircuitState> getQueueCircuitState(PatternAndCircuitHash patternAndCircuitHash);

    /**
     * Get the actual {@link QueueCircuitState} of the provided circuitHash. When no circuit could be found in the
     * storage, {@link QueueCircuitState#CLOSED} is returned.
     *
     * @param circuitHash the hash representing the circuit
     * @return returns the actual {@link QueueCircuitState} or {@link QueueCircuitState#CLOSED} when no circuit was found
     */
    Future<QueueCircuitState> getQueueCircuitState(String circuitHash);

    /**
     * Get the information (status, failRatio and circuit) of the circuit represented by the provided circuitHash.
     *
     * @param circuitHash the hash representing the circuit to get the informations from
     * @return returns a {@link JsonObject} containing the information of the circuit
     */
    Future<JsonObject> getQueueCircuitInformation(String circuitHash);

    /**
     * Get the information (status, failRatio and circuit) of all circuits.
     *
     * @return returns a {@link JsonObject} containing the information of all circuits
     */
    Future<JsonObject> getAllCircuits();

    /**
     * <p>Updates the statistics of the corresponding circuit based on the provided patternAndCircuitHash.</p>
     * <p>Updating the statistics includes the following steps:
     * <ul>
     *     <li>Record uniqueRequestID as failed when queueResponseType equals {@link QueueResponseType#FAILURE}</li>
     *     <li>Record uniqueRequestID as success when queueResponseType equals {@link QueueResponseType#SUCCESS}</li>
     *     <li>Calculate failRatio based on fail/success records not older than entriesMaxAgeMS</li>
     *     <li>Change status of corresponding circuit to 'OPEN' when minQueueSampleCount and errorThresholdPercentage is reached</li>
     *     <li>Remove oldest fail/success records when maxQueueSampleCount is reached</li>
     * </ul>
     *
     *
     * @param patternAndCircuitHash the information of the circuit
     * @param uniqueRequestID the unique identifier of the queued request
     * @param timestamp the current timestamp
     * @param errorThresholdPercentage the threshold to change status to 'OPEN' when reached
     * @param entriesMaxAgeMS the maximum age of fail/success records to respect to calculate the failRatio
     * @param minQueueSampleCount the minimum amount of records (unique uniqueRequestIDs) to reach before status can be changed
     * @param maxQueueSampleCount the maximum amount of fail/success records to keep
     * @param queueResponseType the {@link QueueResponseType} representing the execution result of the queuedRequest
     * @return returns an {@link UpdateStatisticsResult} object representing the result of the statistics update
     */
    Future<UpdateStatisticsResult> updateStatistics(PatternAndCircuitHash patternAndCircuitHash, String uniqueRequestID, long timestamp, int errorThresholdPercentage, long entriesMaxAgeMS, long minQueueSampleCount, long maxQueueSampleCount, QueueResponseType queueResponseType);

    /**
     * Mark the queueName as a locked queue of the circuit representing the provided patternAndCircuitHash.
     *
     * @param queueName the name of the queue
     * @param patternAndCircuitHash the information of the circuit
     * @return returns a void Future when the queue could be successfully marked as locked
     */
    Future<Void> lockQueue(String queueName, PatternAndCircuitHash patternAndCircuitHash);

    /**
     * Get the next queue to unlock. When successful, the queue is removed from the storage.
     *
     * @return returns a Future holding the name of the next queue or <code>null</code> when no queue was found
     */
    Future<String> popQueueToUnlock();

    /**
     * <p>Closes the circuit.</p>
     * <p>Closing the circuit includes the following steps:
     * <ul>
     *     <li>Clear statistics of this circuit</li>
     *     <li>Reset failRatio of this circuit to zero</li>
     *     <li>Set status of this circuit to 'CLOSED'</li>
     *     <li>Unlock all queues related to this circuit</li>
     * </ul>
     *
     *
     * @param patternAndCircuitHash the information of the circuit
     * @return returns a void Future when circuit could be closed successfully.
     */
    Future<Void> closeCircuit(PatternAndCircuitHash patternAndCircuitHash);

    /**
     * Closes the circuit and removes all circuit information from storage.
     *
     * @param patternAndCircuitHash the information of the circuit
     * @return returns a void Future when circuit could be closed and removed successfully.
     */
    Future<Void> closeAndRemoveCircuit(PatternAndCircuitHash patternAndCircuitHash);

    /**
     * <p>Closes all non-closed circuits.</p>
     * <p>Closing all non-closed circuits includes the following steps:
     * <ul>
     *     <li>Clear statistics of all non-closed circuits</li>
     *     <li>Reset failRatio of all non-closed circuits to zero</li>
     *     <li>Set status of all non-closed circuits to 'CLOSED'</li>
     *     <li>Unlock all queues related to all non-closed circuits</li>
     * </ul>
     *
     *
     * @return returns a void Future when all non-closed circuits could be closed successfully.
     */
    Future<Void> closeAllCircuits();

    /**
     * Re-Opens the (half-open) circuit again. Circuits should be re-opened
     * after a sample queue request was not successful.
     *
     * @param patternAndCircuitHash the information of the circuit
     * @return returns a void Future when the circuit could be re-opened successfully
     */
    Future<Void> reOpenCircuit(PatternAndCircuitHash patternAndCircuitHash);

    /**
     * Changes the status of all circuits having a status equals {@link QueueCircuitState#OPEN}
     * to {@link QueueCircuitState#HALF_OPEN}.
     *
     * @return returns a Future holding the amount of circuits updated
     */
    Future<Long> setOpenCircuitsToHalfOpen();

    /**
     * Get a list of sample queues of all circuits having a status equals {@link QueueCircuitState#HALF_OPEN}. The sample
     * queues are always the queues which have not been unlocked the longest. Each sample queue of each circuit is then
     * updated to be the 'most recently' unlocked queue.
     *
     * @return returns a Future holding a list of sample queues to unlock
     */
    Future<Response> unlockSampleQueues();
}

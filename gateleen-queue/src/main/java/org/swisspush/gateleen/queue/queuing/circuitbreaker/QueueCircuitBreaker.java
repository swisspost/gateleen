package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.Future;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitState;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueResponseType;

/**
 * The QueueCircuitBreaker monitors the queue activity and protects the system from too much load when circuits/endpoints
 * are not reachable.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public interface QueueCircuitBreaker {

    /**
     * Handles a queued request by checking the current {@link QueueCircuitState} of the corresponding circuit.
     * When the {@link QueueCircuitState} of the corresponding circuit equals {@link QueueCircuitState#OPEN},
     * the provided queueName will be locked.
     *
     * @param queueName the name of the queue
     * @param queuedRequest the queued request
     * @return returns a {@link QueueCircuitState} representing the current status of the circuit
     */
    Future<QueueCircuitState> handleQueuedRequest(String queueName, HttpRequest queuedRequest);

    /**
     * <p>Updates the statistics of the corresponding circuit based on the provided request to execute.</p>
     * <p>Updating the statistics includes the following steps:
     * <ul>
     *     <li>Record queuedRequest as failed when queueResponseType equals {@link QueueResponseType#FAILURE}</li>
     *     <li>Record queuedRequest as success when queueResponseType equals {@link QueueResponseType#SUCCESS}</li>
     *     <li>Calculate failRatio based on fail/success records</li>
     *     <li>Change status of corresponding circuit to 'OPEN' when failRatio threshold is reached</li>
     * </ul>
     *
     * @param queueName the name of the queue
     * @param queuedRequest the queued request
     * @param queueResponseType the {@link QueueResponseType} representing the execution result of the queuedRequest
     * @return returns a void future when statistics could be updated successfully.
     */
    Future<Void> updateStatistics(String queueName, HttpRequest queuedRequest, QueueResponseType queueResponseType);

    /**
     * Check whether the circuit check is enabled. The circuit check can be enabled/disabled with the 'circuitCheckEnabled'
     * configuration property.
     *
     * @return returns true when circuit check is enabled, false otherwise
     */
    boolean isCircuitCheckEnabled();

    /**
     * Check whether the statistics update is enabled. The statistics update can be enabled/disabled with the 'statisticsUpdateEnabled'
     * configuration property.
     *
     * @return returns true when statistics update is enabled, false otherwise
     */
    boolean isStatisticsUpdateEnabled();

    /**
     * Locks the queue having the provided queueName by calling the vertx-redisques API. Additionally, marks the queueName
     * as a locked queue of the circuit representing the provided queuedRequest.
     *
     * @param queueName the name of the queue
     * @param queuedRequest the queued request
     * @return returns a void future when the queue could be locked successfully through vertx-redisques and also
     * successfully marked as a locked queue of the circuit.
     */
    Future<Void> lockQueue(String queueName, HttpRequest queuedRequest);

    /**
     * Unlocks the queue having the provided queueName by calling the vertx-redisques API.
     *
     * @param queueName the name of the queue
     * @return returns a string future holding the name of the queue when successfully unlocked. Also returns the name
     * of the queue (as failureMessage) when the unlocking failed.
     */
    Future<String> unlockQueue(String queueName);

    /**
     * Unlocks the next queue in-line. Does nothing when there's no next queue to unlock. Returns a <code>null</code>
     * string in this case.
     *
     * @return returns a string future holding the name of the queue which was successfully unlocked. Also returns the name
     * of the queue (as failureMessage) when the next queue could not be unlocked. Returns a <code>null</code> string
     * when no queue was available to unlock.
     */
    Future<String> unlockNextQueue();

    /**
     * <p>Closes the circuit representing the queued request.</p>
     * <p>Closing the circuit includes the following steps:
     * <ul>
     *     <li>Clear statistics of this circuit</li>
     *     <li>Reset failRatio of this circuit to zero</li>
     *     <li>Set status of this circuit to 'CLOSED'</li>
     *     <li>Unlock all queues related to this circuit</li>
     * </ul>
     *
     *
     * @param queuedRequest the queued request
     * @return returns a void future when circuit could be closed successfully.
     */
    Future<Void> closeCircuit(HttpRequest queuedRequest);

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
     * @return returns a void future when all non-closed circuits could be closed successfully.
     */
    Future<Void> closeAllCircuits();

    /**
     * Re-Opens the (half-open) circuit representing the provided queued request again. Circuits should be re-opened
     * after a sample queue request was not successful.
     *
     * @param queuedRequest the queued request
     * @return returns a void future when the circuit representing the provided queued request could be re-opened successfully
     */
    Future<Void> reOpenCircuit(HttpRequest queuedRequest);

    /**
     * Changes the status of all circuits having a status equals {@link QueueCircuitState#OPEN}
     * to {@link QueueCircuitState#HALF_OPEN}.
     *
     * @return returns a future holding the amount of circuits updated
     */
    Future<Long> setOpenCircuitsToHalfOpen();

    /**
     * Unlocks a sample queue of all circuits having a status equals {@link QueueCircuitState#HALF_OPEN}. The sample
     * queues are always the queues which have not been unlocked the longest.
     *
     * @return returns a future holding the amount of unlocked sample queues
     */
    Future<Long> unlockSampleQueues();
}

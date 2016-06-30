package org.swisspush.gateleen.queue.queuing.circuitbreaker;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public interface QueueCircuitBreakerStorage {

    void resetAllEndpoints();
}

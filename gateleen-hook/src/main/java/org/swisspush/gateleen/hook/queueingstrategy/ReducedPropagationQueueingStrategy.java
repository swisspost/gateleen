package org.swisspush.gateleen.hook.queueingstrategy;

/**
 * {@link QueueingStrategy} implementation used when the propagation of changes to the hooked resource should be reduced.
 * Per configured 'intervalMs' (in milliseconds) only 1 change to the hooked resource (without payload) will be propagated.
 *
 * <p>
 * This strategy is used when the hook configuration contains the following properties:
 * </p>
 *
 * <pre><code>
 * "queueingStrategy": {
 *      "type": "reducedPropagation",
 *      "intervalMs": 60000
 * }
 * </code></pre>
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class ReducedPropagationQueueingStrategy extends QueueingStrategy {

    private final long propagationIntervalMs;

    protected ReducedPropagationQueueingStrategy(long propagationIntervalMs) {
        super();
        this.propagationIntervalMs = propagationIntervalMs;
    }

    /**
     * The propagation interval in milliseconds
     * 
     * @return interval in milliseconds
     */
    public long getPropagationIntervalMs() {
        return propagationIntervalMs;
    }
}

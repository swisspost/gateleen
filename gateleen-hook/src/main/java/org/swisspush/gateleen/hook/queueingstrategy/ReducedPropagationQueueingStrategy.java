package org.swisspush.gateleen.hook.queueingstrategy;

/**
 * {@link QueueingStrategy} implementation used when the propagation of changes to the hooked resource should be reduced.
 * Per configured 'interval' (in seconds) only 1 change to the hooked resource (without payload) will be propagated.
 *
 * <p>
 * This strategy is used when the hook configuration contains the following properties:
 * </p>
 *
 * <pre><code>
 * "queueingStrategy": {
 *      "type": "discardPayload",
 *      "interval": 120
 * }
 * </code></pre>
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class ReducedPropagationQueueingStrategy extends QueueingStrategy {

    private final long propagationInterval;

    protected ReducedPropagationQueueingStrategy(long propagationInterval) {
        super();
        this.propagationInterval = propagationInterval;
    }

    /**
     * The propagation interval in seconds
     * 
     * @return interval in seconds
     */
    public long getPropagationInterval() {
        return propagationInterval;
    }
}

package org.swisspush.gateleen.hook.queueingstrategy;

/**
 * {@link QueueingStrategy} implementation used when the payload of queued requests should be discarded.
 *
 * <p>
 * This strategy is used when the hook configuration contains the following properties:
 * </p>
 *
 * <pre><code>
 * "queueingStrategy": {
 *      "type": "discardPayload"
 * }
 * </code></pre>
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class DiscardPayloadQueueingStrategy extends QueueingStrategy {

}

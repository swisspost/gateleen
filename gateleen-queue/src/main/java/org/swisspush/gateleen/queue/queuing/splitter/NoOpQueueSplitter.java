package org.swisspush.gateleen.queue.queuing.splitter;

/**
 * {@inheritDoc}
 */
public class NoOpQueueSplitter implements QueueSplitter {

    @Override
    /**
     * {@inheritDoc}
     */
    public String convertToSubQueue(String queue) {
        return queue;
    }
}

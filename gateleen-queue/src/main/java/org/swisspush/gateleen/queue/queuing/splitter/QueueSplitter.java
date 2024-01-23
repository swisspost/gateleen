package org.swisspush.gateleen.queue.queuing.splitter;

/**
 * Interface for queues configured to be split in sub-queues. The method {@link QueueSplitter#convertToSubQueue(String)}
 * evaluates the convert of the queue name in a sub-queue name.
 *
 * @author https://github.com/gcastaldi [Giannandrea Castaldi]
 */
public interface QueueSplitter {
    /**
     * Convert the queue name in a sub-queue name. If not necessary maintains the initial queue name.
     *
     * @param queue
     * @return sub-queue name
     */
    String convertToSubQueue(String queue);
}

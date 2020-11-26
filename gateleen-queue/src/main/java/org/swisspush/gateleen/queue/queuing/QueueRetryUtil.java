package org.swisspush.gateleen.queue.queuing;

import io.vertx.core.MultiMap;
import org.slf4j.Logger;

/**
 * Utility class for handling queue retries
 */
public class QueueRetryUtil {

    private static final String RETRY_PATTERN = "x-queue-retry-";

    /**
     * Decides whether to retry a queue item based on the x-queue-retry-xxx request header.
     *
     * This method should be called with 'unsuccessful' response status codes, since it does not make sense
     * to retry a 200 OK response.
     */
    static boolean retryQueueItem(MultiMap headers, int responseStatusCode, Logger logger){
        String retryCount;

        retryCount = headers.get(RETRY_PATTERN + responseStatusCode);
        if (retryCount == null) {
            retryCount = headers.get(RETRY_PATTERN + (responseStatusCode / 100) + "xx");
        }

        if(retryCount == null){
            return true; // no retry config, retry forever
        }

        try {
            int count = Integer.parseInt(retryCount);
            return count != 0;
        } catch (NumberFormatException e) {
            logger.warn("Invalid value for queue retry configuration: {}", retryCount);
            return true;
        }
    }
}

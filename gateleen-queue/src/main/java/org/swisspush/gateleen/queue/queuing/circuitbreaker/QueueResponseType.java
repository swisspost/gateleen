package org.swisspush.gateleen.queue.queuing.circuitbreaker;

/**
 * Enumeration to represent the possible result of queued request response.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public enum QueueResponseType {
    SUCCESS(":success"), FAILURE(":failure");

    private String keySuffix;

    QueueResponseType(String keySuffix){
        this.keySuffix = keySuffix;
    }

    public String getKeySuffix() {
        return keySuffix;
    }
}

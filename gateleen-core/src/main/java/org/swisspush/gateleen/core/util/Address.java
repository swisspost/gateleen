package org.swisspush.gateleen.core.util;

import java.util.UUID;

/**
 * Provides the event bus addresses in a cluster-aware way.
 */
public final class Address {
    private static final String ID = UUID.randomUUID().toString();

    public static final String RULE_UPDATE_ADDRESS = "gateleen.routing-rules-updated";

    private Address(){}

    public static String storageAddress() {
        return "resource-storage-"+ID;
    }

    public static String redisquesAddress() { return "redisques-address-"+ID; }

    public static String requestLoggingConsumerAddress() { return "request-logging-consumer-address-"+ID; }

    public static String customRedisquesAddress(String redisquesAddress) {
        return redisquesAddress + "-" + ID;
    }

    public static String queueProcessorAddress() {
        return "redisques-processor-"+ID;
    }

    public static String customQueueProcessorAddress(String queueProcessorAddress) { return queueProcessorAddress + "-" + ID; }

    public static String monitoringAddress(){ return "org.swisspush.metrics-"+ID; }

    public static String customMonitoringAddress(String customMonitoringAddress){ return customMonitoringAddress + "-" + ID; }
}

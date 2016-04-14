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

    public static String queueProcessorAddress() {
        return "redisques-processor-"+ID;
    }

    public static String monitoringAddress(){
        return "org.swisspush.metrics-"+ID;
    }
}

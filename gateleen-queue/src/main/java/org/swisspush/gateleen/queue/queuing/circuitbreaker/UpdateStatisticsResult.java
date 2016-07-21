package org.swisspush.gateleen.queue.queuing.circuitbreaker;

/**
 * Enumeration to represent the outcome of a statistics update.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public enum UpdateStatisticsResult {
    ERROR, OK, OPENED;

    /**
     * Returns the enum UpdateStatisticsResult which matches the specified String value.
     *
     * @param stringValue The UpdateStatisticsResult value as String
     * @param defaultReturnValue The UpdateStatisticsResult to return when nothing matches (or null was provided)
     * @return The matching UpdateStatisticsResult or the provided default value if none matches.
     */
    public static UpdateStatisticsResult fromString(String stringValue, UpdateStatisticsResult defaultReturnValue) {
        for (UpdateStatisticsResult updateStatisticsResult : values()) {
            if (updateStatisticsResult.name().equalsIgnoreCase(stringValue)) {
                return updateStatisticsResult;
            }
        }
        return defaultReturnValue;
    }
}

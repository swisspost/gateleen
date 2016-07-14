package org.swisspush.gateleen.queue.queuing.circuitbreaker;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class QueueCircuitBreakerConfigurationResource {

    private boolean circuitCheckEnabled;
    private boolean statisticsUpdateEnabled;
    private int errorThresholdPercentage;
    private int entriesMaxAgeMS;
    private int minQueueSampleCount;
    private int maxQueueSampleCount;

    public static final int DEFAULT_ERROR_THRESHOLD = 90;
    public static final int DEFAULT_ENTRY_MAX_AGE = 86400000; // 24h
    public static final int DEFAULT_MIN_SAMPLE_COUNT = 100;
    public static final int DEFAULT_MAX_SAMPLE_COUNT = 5000;

    public QueueCircuitBreakerConfigurationResource(){
        reset();
    }

    /**
     * Resets all configuration values to the default values
     */
    public void reset() {
        circuitCheckEnabled = false;
        statisticsUpdateEnabled = false;
        errorThresholdPercentage = DEFAULT_ERROR_THRESHOLD;
        entriesMaxAgeMS = DEFAULT_ENTRY_MAX_AGE;
        minQueueSampleCount = DEFAULT_MIN_SAMPLE_COUNT;
        maxQueueSampleCount = DEFAULT_MAX_SAMPLE_COUNT;
    }

    public boolean isCircuitCheckEnabled() {
        return circuitCheckEnabled;
    }

    public void setCircuitCheckEnabled(boolean circuitCheckEnabled) {
        this.circuitCheckEnabled = circuitCheckEnabled;
    }

    public boolean isStatisticsUpdateEnabled() {
        return statisticsUpdateEnabled;
    }

    public void setStatisticsUpdateEnabled(boolean statisticsUpdateEnabled) {
        this.statisticsUpdateEnabled = statisticsUpdateEnabled;
    }

    public int getErrorThresholdPercentage() { return errorThresholdPercentage; }

    public void setErrorThresholdPercentage(int errorThresholdPercentage) {
        this.errorThresholdPercentage = errorThresholdPercentage;
    }

    public int getEntriesMaxAgeMS() { return entriesMaxAgeMS; }

    public void setEntriesMaxAgeMS(int entriesMaxAgeMS) { this.entriesMaxAgeMS = entriesMaxAgeMS; }

    public int getMinQueueSampleCount() { return minQueueSampleCount; }

    public void setMinQueueSampleCount(int minQueueSampleCount) { this.minQueueSampleCount = minQueueSampleCount; }

    public int getMaxQueueSampleCount() { return maxQueueSampleCount; }

    public void setMaxQueueSampleCount(int maxQueueSampleCount) { this.maxQueueSampleCount = maxQueueSampleCount; }

    @Override
    public String toString() {
        return "{circuitCheckEnabled=" + circuitCheckEnabled +
               ", statisticsUpdateEnabled=" + statisticsUpdateEnabled +
               ", errorThresholdPercentage=" + errorThresholdPercentage +
               ", entriesMaxAgeMS=" + entriesMaxAgeMS +
               ", minQueueSampleCount=" + minQueueSampleCount +
               ", maxQueueSampleCount=" + maxQueueSampleCount +
               "}";
    }
}

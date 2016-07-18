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
    private boolean openToHalfOpenTaskEnabled;
    private int openToHalfOpenTaskInterval;
    private boolean unlockQueuesTaskEnabled;
    private int unlockQueuesTaskInterval;
    private boolean unlockSampleQueuesTaskEnabled;
    private int unlockSampleQueuesTaskInterval;

    private static final int DEFAULT_ERROR_THRESHOLD = 90;
    private static final int DEFAULT_ENTRY_MAX_AGE = 86400000; // 24h
    private static final int DEFAULT_MIN_SAMPLE_COUNT = 100;
    private static final int DEFAULT_MAX_SAMPLE_COUNT = 5000;
    private static final int DEFAULT_TO_HALFOPEN_INTERVAL = 30000; // 30s
    private static final int DEFAULT_UNLOCK_QUEUES_INTERVAL = 20000; // 20s
    private static final int DEFAULT_UNLOCK_SAMPLE_QUEUES_INTERVAL = 20000; // 20s

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

        openToHalfOpenTaskEnabled = false;
        openToHalfOpenTaskInterval = DEFAULT_TO_HALFOPEN_INTERVAL;

        unlockQueuesTaskEnabled = false;
        unlockQueuesTaskInterval = DEFAULT_UNLOCK_QUEUES_INTERVAL;

        unlockSampleQueuesTaskEnabled = false;
        unlockSampleQueuesTaskInterval = DEFAULT_UNLOCK_SAMPLE_QUEUES_INTERVAL;
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

    public boolean isOpenToHalfOpenTaskEnabled() { return openToHalfOpenTaskEnabled; }

    public void setOpenToHalfOpenTaskEnabled(boolean openToHalfOpenTaskEnabled) {
        this.openToHalfOpenTaskEnabled = openToHalfOpenTaskEnabled;
    }

    public int getOpenToHalfOpenTaskInterval() { return openToHalfOpenTaskInterval; }

    public void setOpenToHalfOpenTaskInterval(int openToHalfOpenTaskInterval) {
        this.openToHalfOpenTaskInterval = openToHalfOpenTaskInterval;
    }

    public int getUnlockQueuesTaskInterval() { return unlockQueuesTaskInterval; }

    public void setUnlockQueuesTaskInterval(int unlockQueuesTaskInterval) {
        this.unlockQueuesTaskInterval = unlockQueuesTaskInterval;
    }

    public boolean isUnlockQueuesTaskEnabled() {
        return unlockQueuesTaskEnabled;
    }

    public void setUnlockQueuesTaskEnabled(boolean unlockQueuesTaskEnabled) {
        this.unlockQueuesTaskEnabled = unlockQueuesTaskEnabled;
    }

    public boolean isUnlockSampleQueuesTaskEnabled() { return unlockSampleQueuesTaskEnabled; }

    public void setUnlockSampleQueuesTaskEnabled(boolean unlockSampleQueuesTaskEnabled) {
        this.unlockSampleQueuesTaskEnabled = unlockSampleQueuesTaskEnabled;
    }

    public int getUnlockSampleQueuesTaskInterval() { return unlockSampleQueuesTaskInterval; }

    public void setUnlockSampleQueuesTaskInterval(int unlockSampleQueuesTaskInterval) {
        this.unlockSampleQueuesTaskInterval = unlockSampleQueuesTaskInterval;
    }

    @Override
    public String toString() {
        return "{circuitCheckEnabled=" + circuitCheckEnabled +
               ", statisticsUpdateEnabled=" + statisticsUpdateEnabled +
               ", errorThresholdPercentage=" + errorThresholdPercentage +
               ", entriesMaxAgeMS=" + entriesMaxAgeMS +
               ", minQueueSampleCount=" + minQueueSampleCount +
               ", maxQueueSampleCount=" + maxQueueSampleCount +
               ", openToHalfOpenTaskEnabled=" + openToHalfOpenTaskEnabled +
               ", openToHalfOpenTaskInterval=" + openToHalfOpenTaskInterval +
               ", unlockQueuesTaskEnabled=" + unlockQueuesTaskEnabled +
               ", unlockQueuesTaskInterval=" + unlockQueuesTaskInterval +
               ", unlockSampleQueuesTaskEnabled=" + unlockSampleQueuesTaskEnabled +
               ", unlockSampleQueuesTaskInterval=" + unlockSampleQueuesTaskInterval +
               "}";
    }
}

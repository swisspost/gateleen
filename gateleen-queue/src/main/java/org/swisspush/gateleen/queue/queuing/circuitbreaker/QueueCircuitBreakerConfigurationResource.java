package org.swisspush.gateleen.queue.queuing.circuitbreaker;

/**
 * Container class for all available {@link QueueCircuitBreaker} configuration values.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
class QueueCircuitBreakerConfigurationResource {

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

    QueueCircuitBreakerConfigurationResource(){
        reset();
    }

    /**
     * Resets all configuration values to the default values
     */
    void reset() {
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

    boolean isCircuitCheckEnabled() {
        return circuitCheckEnabled;
    }

    void setCircuitCheckEnabled(boolean circuitCheckEnabled) {
        this.circuitCheckEnabled = circuitCheckEnabled;
    }

    boolean isStatisticsUpdateEnabled() {
        return statisticsUpdateEnabled;
    }

    void setStatisticsUpdateEnabled(boolean statisticsUpdateEnabled) {
        this.statisticsUpdateEnabled = statisticsUpdateEnabled;
    }

    int getErrorThresholdPercentage() { return errorThresholdPercentage; }

    void setErrorThresholdPercentage(int errorThresholdPercentage) {
        this.errorThresholdPercentage = errorThresholdPercentage;
    }

    int getEntriesMaxAgeMS() { return entriesMaxAgeMS; }

    void setEntriesMaxAgeMS(int entriesMaxAgeMS) { this.entriesMaxAgeMS = entriesMaxAgeMS; }

    int getMinQueueSampleCount() { return minQueueSampleCount; }

    void setMinQueueSampleCount(int minQueueSampleCount) { this.minQueueSampleCount = minQueueSampleCount; }

    int getMaxQueueSampleCount() { return maxQueueSampleCount; }

    void setMaxQueueSampleCount(int maxQueueSampleCount) { this.maxQueueSampleCount = maxQueueSampleCount; }

    boolean isOpenToHalfOpenTaskEnabled() { return openToHalfOpenTaskEnabled; }

    void setOpenToHalfOpenTaskEnabled(boolean openToHalfOpenTaskEnabled) {
        this.openToHalfOpenTaskEnabled = openToHalfOpenTaskEnabled;
    }

    int getOpenToHalfOpenTaskInterval() { return openToHalfOpenTaskInterval; }

    void setOpenToHalfOpenTaskInterval(int openToHalfOpenTaskInterval) {
        this.openToHalfOpenTaskInterval = openToHalfOpenTaskInterval;
    }

    int getUnlockQueuesTaskInterval() { return unlockQueuesTaskInterval; }

    void setUnlockQueuesTaskInterval(int unlockQueuesTaskInterval) {
        this.unlockQueuesTaskInterval = unlockQueuesTaskInterval;
    }

    boolean isUnlockQueuesTaskEnabled() {
        return unlockQueuesTaskEnabled;
    }

    void setUnlockQueuesTaskEnabled(boolean unlockQueuesTaskEnabled) {
        this.unlockQueuesTaskEnabled = unlockQueuesTaskEnabled;
    }

    boolean isUnlockSampleQueuesTaskEnabled() { return unlockSampleQueuesTaskEnabled; }

    void setUnlockSampleQueuesTaskEnabled(boolean unlockSampleQueuesTaskEnabled) {
        this.unlockSampleQueuesTaskEnabled = unlockSampleQueuesTaskEnabled;
    }

    int getUnlockSampleQueuesTaskInterval() { return unlockSampleQueuesTaskInterval; }

    void setUnlockSampleQueuesTaskInterval(int unlockSampleQueuesTaskInterval) {
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

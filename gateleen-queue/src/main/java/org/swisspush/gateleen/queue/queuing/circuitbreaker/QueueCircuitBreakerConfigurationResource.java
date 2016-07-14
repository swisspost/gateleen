package org.swisspush.gateleen.queue.queuing.circuitbreaker;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class QueueCircuitBreakerConfigurationResource {

    private boolean circuitCheckEnabled;
    private boolean statisticsUpdateEnabled;
    private int errorThresholdPercentage;

    public static final int DEFAULT_ERROR_THRESHOLD = 90;

    public QueueCircuitBreakerConfigurationResource(){
        circuitCheckEnabled = false;
        statisticsUpdateEnabled = false;
        errorThresholdPercentage = DEFAULT_ERROR_THRESHOLD;
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

    public int getErrorThresholdPercentage() {
        return errorThresholdPercentage;
    }

    public void setErrorThresholdPercentage(int errorThresholdPercentage) {
        this.errorThresholdPercentage = errorThresholdPercentage;
    }

    /**
     * Clears all configuration resource values
     */
    public void reset() {
        circuitCheckEnabled = false;
        statisticsUpdateEnabled = false;
        errorThresholdPercentage = DEFAULT_ERROR_THRESHOLD;
    }

    @Override
    public String toString() {
        return "{" +
                "circuitCheckEnabled=" + circuitCheckEnabled +
                ", statisticsUpdateEnabled=" + statisticsUpdateEnabled +
                ", errorThresholdPercentage=" + errorThresholdPercentage +
                '}';
    }
}

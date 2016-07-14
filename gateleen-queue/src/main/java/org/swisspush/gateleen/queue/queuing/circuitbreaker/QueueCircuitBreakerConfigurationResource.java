package org.swisspush.gateleen.queue.queuing.circuitbreaker;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class QueueCircuitBreakerConfigurationResource {

    private boolean circuitCheckEnabled = false;
    private boolean statisticsUpdateEnabled = false;

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

    /**
     * Clears all configuration resource values
     */
    public void reset() {
        circuitCheckEnabled = false;
        statisticsUpdateEnabled = false;
    }

    @Override
    public String toString() {
        return "{" +
                "circuitCheckEnabled=" + circuitCheckEnabled +
                ", statisticsUpdateEnabled=" + statisticsUpdateEnabled +
                '}';
    }
}

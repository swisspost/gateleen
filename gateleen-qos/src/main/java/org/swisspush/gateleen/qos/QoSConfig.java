package org.swisspush.gateleen.qos;

/**
 * Represents the global configuration of
 * the QoS feature.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class QoSConfig {
    private int percentile;
    private int quorum;
    private int period;
    private int minSampleCount;
    private int minSentinelCount;

    /**
     * Creates a new global configuration
     * object for the QoS.
     * 
     * @param percentile the percentile which should be read from the metrics (sentinels).
     * @param quorum defines how many percent of all api's must be over the defined ratio, so the given api is rejected.
     * @param period defines the period in seconds after which the calculation is executed.
     * @param minSampleCount defines the min. samples count of a sentinel so it can be used for the QoS calc.
     * @param minSentinelCount defines the min. sentinel count used to perform a QoS calc.
     */
    public QoSConfig(int percentile, int quorum, int period, int minSampleCount, int minSentinelCount) {
        this.percentile = percentile;
        this.quorum = quorum;
        this.period = period;
        this.minSampleCount = minSampleCount;
        this.minSentinelCount = minSentinelCount;
    }

    /**
     * Returns the percentile which should be
     * read from the metrics (sentinels).
     * 
     * @return the percentile value
     */
    protected int getPercentile() {
        return percentile;
    }

    /**
     * Returns how many percent of all api's must
     * be over the defined ratio, so the given api
     * is rejected.
     * 
     * @return a percent value
     */
    protected int getQuorum() {
        return quorum;
    }

    /**
     * Returns the period in seconds, after which the QoS
     * calculation is executed.
     * 
     * @return time value in seconds
     */
    protected int getPeriod() {
        return period;
    }

    /**
     * Returns the amount of samples which a
     * sentinel has to provide to be counted as
     * a valid sentinel.
     * 
     * @return min amount of samples
     */
    public int getMinSampleCount() {
        return minSampleCount;
    }

    /**
     * Returns the amount of sentinels which
     * must be available to perform a
     * QoS calculation.
     * 
     * @return min amount of sentinels
     */
    public int getMinSentinelCount() {
        return minSentinelCount;
    }
}

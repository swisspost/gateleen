package org.swisspush.gateleen.qos;

/**
 * Represents a sentinel of the QoS.
 * A sentinel is normaly a metric which is
 * used to calculate if a request has to be
 * rejected or not. <br>
 * The sentinel names are used / set in the
 * routing rules.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class QoSSentinel {
    private String name;
    private Integer percentile = null;
    private double lowestPercentileValue;

    /**
     * Creates a new sentinel with the given
     * name.
     * 
     * @param name the name of this sentinel (metric).
     */
    public QoSSentinel(String name) {
        this.name = name;
        lowestPercentileValue = Double.MAX_VALUE;
    }

    /**
     * Returns a percentile which is used
     * to override the global setting for
     * this specific metric. <br>
     * If no override is set, null will
     * be returned.
     * 
     * @return if set the override percentile, otherwise null
     */
    public Integer getPercentile() {
        return percentile;
    }

    /**
     * Sets the percentile to override the global setting.
     * 
     * @param percentile
     */
    public void setPercentile(int percentile) {
        this.percentile = percentile;
    }

    /**
     * Returns the name of this sentinel.
     * 
     * @return name of the sentinel (metric).
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the lowest measured percentile value
     * of this sentinel.
     * 
     * @return lowest measured percentile value
     */
    public double getLowestPercentileValue() {
        return lowestPercentileValue;
    }

    /**
     * Sets the lowest measured percentile value
     * of this sentinel.
     * 
     * @param lowestPercentileValue lowest measured percentile value
     */
    public void setLowestPercentileValue(double lowestPercentileValue) {
        this.lowestPercentileValue = lowestPercentileValue;
    }
}

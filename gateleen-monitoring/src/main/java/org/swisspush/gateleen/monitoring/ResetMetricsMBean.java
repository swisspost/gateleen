package org.swisspush.gateleen.monitoring;

/**
 * MBean defining operations to reset metric values from JMX
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public interface ResetMetricsMBean {

    void resetLastUsedQueueSizeInformation();

    void resetRequestsFromClientsToCrushCount();

    void resetRequestsFromCrushToBackendsCount();

    void resetMetricByName(String mBeanName);
}

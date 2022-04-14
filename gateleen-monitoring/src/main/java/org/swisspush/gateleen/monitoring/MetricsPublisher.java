package org.swisspush.gateleen.monitoring;

public interface MetricsPublisher {

    void publishMetric(String name, long value);
}

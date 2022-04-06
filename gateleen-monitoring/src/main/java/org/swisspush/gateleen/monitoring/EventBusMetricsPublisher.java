package org.swisspush.gateleen.monitoring;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

public class EventBusMetricsPublisher implements MetricsPublisher {

    private final Vertx vertx;
    private final String monitoringAddress;
    private final String prefix;

    public EventBusMetricsPublisher(Vertx vertx, String monitoringAddress, String prefix) {
        this.vertx = vertx;
        this.monitoringAddress = monitoringAddress;
        this.prefix = prefix;
    }

    @Override
    public void publishMetric(String name, long value) {
        vertx.eventBus().publish(monitoringAddress,
                new JsonObject().put("name", prefix + name).put("action", "set").put("n", value));
    }
}

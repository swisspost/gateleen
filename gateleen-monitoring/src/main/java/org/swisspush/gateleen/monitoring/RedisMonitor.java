package org.swisspush.gateleen.monitoring;

import org.swisspush.gateleen.core.util.Address;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.RedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Monitors regularly redis info metrics and arbitrary commands. Sends the results to metrics.
 */
public class RedisMonitor {

    private Vertx vertx;
    private RedisClient redisClient;
    private int period;
    private long timer;
    private Logger log = LoggerFactory.getLogger(RedisMonitor.class);
    private String prefix;

    private String metricName;
    private String elementCountKey;

    /**
     * @param vertx vertx
     * @param redisClient redisClient
     * @param name name
     * @param period in seconds.
     */
    public RedisMonitor(Vertx vertx, RedisClient redisClient, String name, int period) {
        this.vertx = vertx;
        this.redisClient = redisClient;
        this.period = period * 1000;
        this.prefix = "redis." + name + ".";
    }

    public void start() {
        timer = vertx.setPeriodic(period, timer -> {
            redisClient.info(reply -> {
                if(reply.succeeded()){
                    collectMetrics(reply.result());
                } else {
                    log.warn("Cannot collect INFO from redis");
                }
            });

            if(metricName != null && elementCountKey != null){
                redisClient.zcard(elementCountKey, reply -> {
                    if(reply.succeeded()){
                        long value = reply.result();
                        vertx.eventBus().publish(getMonitoringAddress(),
                                new JsonObject().put("name", prefix + metricName).put("action", "set").put("n", value));
                    } else {
                        log.warn("Cannot collect zcard from redis for key " + elementCountKey);
                    }
                });
            }
        });
    }

    public void stop() {
        if (timer != 0) {
            vertx.cancelTimer(timer);
        }
    }

    /**
     * Get the event bus address of the monitoring.
     * Override this method when you want to use a custom monitoring address
     *
     * @return the event bus address of monitoring
     */
    protected String getMonitoringAddress(){
        return Address.monitoringAddress();
    }

    public void enableElementCount(String metricName, String key){
        this.metricName = metricName;
        this.elementCountKey = key;
    }

    private void collectMetrics(JsonObject info) {
        for (String fieldName : info.fieldNames()) {
            Object field = info.getValue(fieldName);
            if (field instanceof JsonObject) {
                for (String sectionFieldName : ((JsonObject) field).fieldNames()) {
                    if ("keyspace".equals(fieldName)) {
                        String[] pairs = ((JsonObject) field).getString(sectionFieldName).split(",");
                        for (String pair : pairs) {
                            String[] tokens = pair.split("=");
                            sendMetric(fieldName + "." + sectionFieldName + "." + tokens[0], tokens[1]);
                        }
                    } else {
                        sendMetric(fieldName + "." + sectionFieldName, ((JsonObject) field).getString(sectionFieldName));
                    }
                }
            } else {
                sendMetric(fieldName, field.toString());
            }
        }
    }

    private void sendMetric(String name, String stringValue) {
        if (stringValue == null) {
            return;
        }
        long value = 0;
        try {
            if (name.contains("_cpu_")) {
                value = (long) (Double.parseDouble(stringValue) * 1000.0);
            } else {
                value = Long.parseLong(stringValue);
            }
            vertx.eventBus().publish(getMonitoringAddress(),
                    new JsonObject().put("name", prefix + name).put("action", "set").put("n", value));
        } catch (NumberFormatException e) {
            // ignore this field
        }
    }
}

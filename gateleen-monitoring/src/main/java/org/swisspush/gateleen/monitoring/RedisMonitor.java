package org.swisspush.gateleen.monitoring;

import com.google.common.base.Splitter;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.redis.RedisProvider;
import org.swisspush.gateleen.core.util.Address;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Monitors regularly redis info metrics and arbitrary commands. Sends the results to metrics.
 */
public class RedisMonitor {

    private Vertx vertx;
    private RedisProvider redisProvider;
    private int period;
    private long timer;
    private Logger log = LoggerFactory.getLogger(RedisMonitor.class);

    private String metricName;
    private String elementCountKey;

    private static final String DELIMITER = ":";

    private final MetricsPublisher publisher;

    /**
     * @param vertx         vertx
     * @param redisProvider RedisProvider
     * @param name          name
     * @param period        in seconds.
     */
    public RedisMonitor(Vertx vertx, RedisProvider redisProvider, String name, int period) {
        this(vertx, redisProvider, name, period,
                new EventBusMetricsPublisher(vertx, Address.monitoringAddress(), "redis." + name + ".")
        );
    }

    public RedisMonitor(Vertx vertx, RedisProvider redisProvider, String name, int period, MetricsPublisher publisher) {
        this.vertx = vertx;
        this.redisProvider = redisProvider;
        this.period = period * 1000;
        this.publisher = publisher;
    }

    public void start() {
        timer = vertx.setPeriodic(period, timer -> {
            redisProvider.redis().onSuccess(redisAPI -> {
                redisAPI.info(new ArrayList<>()).onComplete(event -> {
                    if (event.succeeded()) {
                        collectMetrics(event.result().toBuffer());
                    } else {
                        log.warn("Cannot collect INFO from redis");
                    }
                });

                if (metricName != null && elementCountKey != null) {
                    redisAPI.zcard(elementCountKey, reply -> {
                        if (reply.succeeded()) {
                            long value = reply.result().toLong();
                            publisher.publishMetric(metricName, value);
                        } else {
                            log.warn("Cannot collect zcard from redis for key {}", elementCountKey);
                        }
                    });
                }
            }).onFailure(throwable -> log.warn("Cannot collect INFO from redis", throwable));
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
    protected String getMonitoringAddress() {
        return Address.monitoringAddress();
    }

    public void enableElementCount(String metricName, String key) {
        this.metricName = metricName;
        this.elementCountKey = key;
    }

    private void collectMetrics(Buffer buffer) {
        Map<String, String> map = new HashMap<>();

        Splitter.on(System.lineSeparator()).omitEmptyStrings()
                .trimResults().splitToList(buffer.toString()).stream()
                .filter(input -> input != null && input.contains(DELIMITER)
                        && !input.contains("executable")
                        && !input.contains("config_file")).forEach(entry -> {
                    List<String> keyValue = Splitter.on(DELIMITER).omitEmptyStrings().trimResults().splitToList(entry);
                    if (keyValue.size() == 2) {
                        map.put(keyValue.get(0), keyValue.get(1));
                    }
                });

        log.debug("got redis metrics {}", map);

        map.forEach((key, valueStr) -> {
            long value;
            try {
                if (key.startsWith("db")) {
                    String[] pairs = valueStr.split(",");
                    for (String pair : pairs) {
                        String[] tokens = pair.split("=");
                        if (tokens.length == 2) {
                            value = Long.parseLong(tokens[1]);
                            publisher.publishMetric("keyspace." + key + "." + tokens[0], value);
                        } else {
                            log.warn("Invalid keyspace property. Will be ignored");
                        }
                    }
                } else if (key.contains("_cpu_")) {
                    value = (long) (Double.parseDouble(valueStr) * 1000.0);
                    publisher.publishMetric(key, value);
                } else if (key.contains("fragmentation_ratio")) {
                    value = (long) (Double.parseDouble(valueStr));
                    publisher.publishMetric(key, value);
                } else {
                    value = Long.parseLong(valueStr);
                    publisher.publishMetric(key, value);
                }
            } catch (NumberFormatException e) {
                // ignore this field
            }
        });
    }
}

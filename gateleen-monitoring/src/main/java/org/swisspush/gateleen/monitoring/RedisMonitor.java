package org.swisspush.gateleen.monitoring;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.redis.client.RedisAPI;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.Address;

import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * Monitors regularly redis info metrics and arbitrary commands. Sends the results to metrics.
 */
public class RedisMonitor {

    private Vertx vertx;
    private RedisAPI redisAPI;
    private int period;
    private long timer;
    private Logger log = LoggerFactory.getLogger(RedisMonitor.class);
    private String prefix;

    private String metricName;
    private String elementCountKey;

    private static final String DELIMITER = ":";

    private final MetricsPublisher publisher;

    /**
     * @param vertx    vertx
     * @param redisAPI redisAPI
     * @param name     name
     * @param period   in seconds.
     */
    public RedisMonitor(Vertx vertx, RedisAPI redisAPI, String name, int period) {
        this(vertx, redisAPI, name, period,
                new EventBusMetricsPublisher(vertx, Address.monitoringAddress(), "redis." + name + ".")
        );
    }

    public RedisMonitor(Vertx vertx, RedisAPI redisAPI, String name, int period, MetricsPublisher publisher) {
        this.vertx = vertx;
        this.redisAPI = redisAPI;
        this.period = period * 1000;
        this.prefix = "redis." + name + ".";
        this.publisher = publisher;
    }

    public void start() {
        timer = vertx.setPeriodic(period, timer -> {
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
                        publisher.publishMetric(prefix + metricName, value);
                    } else {
                        log.warn("Cannot collect zcard from redis for key {}", elementCountKey);
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
    protected String getMonitoringAddress() {
        return Address.monitoringAddress();
    }

    public void enableElementCount(String metricName, String key) {
        this.metricName = metricName;
        this.elementCountKey = key;
    }

    private void collectMetrics(Buffer buffer) {
        Splitter.on(System.lineSeparator()).omitEmptyStrings()
                .trimResults().splitToList(buffer.toString()).stream()
                .filter((Predicate<String>) input -> input.contains(DELIMITER) && !input.contains("executable") && !input.contains("config_file"))
                .collect(Collectors.toMap(new Function<>() {
                    @Nullable
                    @Override
                    public String apply(@Nullable String input) {
                        return input.split(DELIMITER)[0];
                    }
                }, c -> c.split(DELIMITER)[1])).forEach((keyObj, val) -> {
            String key = (String) keyObj;
            long value = 0;
            try {
                if (key.startsWith("db")) {
                    String[] pairs = val.split(",");
                    for (String pair : pairs) {
                        String[] tokens = pair.split("=");
                        value = Long.parseLong(tokens[1]);
                        publisher.publishMetric("keyspace." + key + "." + tokens[0], value);
                    }
                } else if (key.contains("_cpu_")) {
                    value = (long) (Double.parseDouble(val) * 1000.0);
                } else if (key.contains("fragmentation_ratio")) {
                    value = (long) (Double.parseDouble(val));
                } else {
                    value = Long.parseLong(val);
                }
                publisher.publishMetric(key, value);
            } catch (Exception e) {
                // ignore this field
            }
        });
    }
}

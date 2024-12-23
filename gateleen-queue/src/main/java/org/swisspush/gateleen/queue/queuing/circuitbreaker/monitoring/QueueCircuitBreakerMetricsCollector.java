package org.swisspush.gateleen.queue.queuing.circuitbreaker.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.core.*;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;
import org.swisspush.gateleen.core.lock.Lock;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.LockUtil;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.QueueCircuitBreakerStorage;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitState;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.swisspush.gateleen.core.util.LockUtil.acquireLock;
import static org.swisspush.gateleen.core.util.LockUtil.calcLockExpiry;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.impl.RedisQueueCircuitBreakerStorage.*;

public class QueueCircuitBreakerMetricsCollector {

    private final Logger log = LoggerFactory.getLogger(QueueCircuitBreakerMetricsCollector.class);

    private final Lock lock;
    private final LockUtil lockUtil;

    public static final String COLLECT_METRICS_TASK_LOCK = "collectCircuitBreakerMetrics";
    public static final String CIRCUIT_BREAKER_STATUS_METRIC = "gateleen.circuitbreaker.status";
    public static final String CIRCUIT_BREAKER_FAILRATIO_METRIC = "gateleen.circuitbreaker.failratio";

    private final QueueCircuitBreakerStorage queueCircuitBreakerStorage;
    private final MeterRegistry meterRegistry;
    private final long metricCollectionIntervalMs;

    private final Map<String, AtomicInteger> circuitStateMap = new HashMap<>();
    private final Map<String, AtomicInteger> circuitFailRatioMap = new HashMap<>();

    public QueueCircuitBreakerMetricsCollector(Vertx vertx, Lock lock, QueueCircuitBreakerStorage queueCircuitBreakerStorage,
                                               MeterRegistry meterRegistry, GateleenExceptionFactory exceptionFactory,
                                               long metricCollectionIntervalSeconds) {
        this.lock = lock;
        this.lockUtil = new LockUtil(exceptionFactory);
        this.queueCircuitBreakerStorage = queueCircuitBreakerStorage;
        this.meterRegistry = meterRegistry;

        this.metricCollectionIntervalMs = metricCollectionIntervalSeconds * 1000;

        vertx.setPeriodic(metricCollectionIntervalMs, event -> {
            collectMetrics().onFailure(event1 -> log.error("Could not collect metrics. Message: {}", event1.getMessage()));
        });
    }

    public Future<Void> collectMetrics() {
        log.debug("Collecting metrics");
        Promise<Void> promise = Promise.promise();
        final String token = createToken(COLLECT_METRICS_TASK_LOCK);
        acquireLock(lock, COLLECT_METRICS_TASK_LOCK, token, calcLockExpiry(metricCollectionIntervalMs), log).onComplete(lockEvent -> {
            if (lockEvent.succeeded()) {
                if (lockEvent.result()) {
                    handleMetricsCollection(token).onComplete(event -> {
                        if (event.succeeded()) {
                            promise.complete();
                        } else {
                            promise.fail(event.cause());
                        }
                    });
                } else {
                    promise.complete();
                }
            } else {
                log.error("Could not acquire lock '{}'. Message: {}", COLLECT_METRICS_TASK_LOCK, lockEvent.cause().getMessage());
                promise.fail(lockEvent.cause().getMessage());
            }
        });
        return promise.future();
    }

    private Future<Void> handleMetricsCollection(String token) {
        return queueCircuitBreakerStorage.getAllCircuits().compose((Function<JsonObject, Future<Void>>) entries -> {
            extractMetricsFromCircuitsObject(entries);
            return Future.succeededFuture();
        }).andThen(event -> lockUtil.releaseLock(lock, COLLECT_METRICS_TASK_LOCK, token, log));
    }

    private void extractMetricsFromCircuitsObject(JsonObject circuits) {
        circuits.stream().forEach(entry -> {
            String circuitName = entry.getKey();
            JsonObject circuitValue = (JsonObject) entry.getValue();
            QueueCircuitState queueCircuitState = QueueCircuitState.fromString(circuitValue.getString(FIELD_STATUS), null);
            if (queueCircuitState == null) {
                log.warn("No status found for circuit '{}'", circuitName);
                return;
            }

            JsonObject infos = circuitValue.getJsonObject("infos");
            if (infos != null) {
                String metric = infos.getString(FIELD_METRICNAME);
                Integer failRatio = infos.getInteger(FIELD_FAILRATIO);
                if (metric != null && failRatio != null) {
                    publishMetric(metric, queueCircuitState, failRatio);
                }
            }
        });
    }

    private void publishMetric(String metricName, QueueCircuitState queueCircuitState, int failRatio) {
        Integer stateValue = circuitStateToValue(queueCircuitState);
        if(stateValue != null) {
            getCircuitStateMeter(metricName).set(stateValue);
        }
        getCircuitFailRatioMeter(metricName).set(failRatio);
    }

    private String createToken(String appendix) {
        return Address.instanceAddress() + "_" + System.currentTimeMillis() + "_" + appendix;
    }

    private AtomicInteger getCircuitStateMeter(String metricName) {
        return circuitStateMap.computeIfAbsent(metricName, key -> {
            AtomicInteger newMeterValue = new AtomicInteger();
            Gauge.builder(CIRCUIT_BREAKER_STATUS_METRIC, newMeterValue, AtomicInteger::get)
                    .description("Status of the circuit, 0=CLOSED, 1=HALF_OPEN, 2=OPEN")
                    .tag("metricName", metricName)
                    .register(meterRegistry);
            return newMeterValue;
        });
    }

    private AtomicInteger getCircuitFailRatioMeter(String metricName) {
        return circuitFailRatioMap.computeIfAbsent(metricName, key -> {
            AtomicInteger newMeterValue = new AtomicInteger();
            Gauge.builder(CIRCUIT_BREAKER_FAILRATIO_METRIC, newMeterValue, AtomicInteger::get)
                    .description("Fail ratio of the circuit in percentage")
                    .tag("metricName", metricName)
                    .register(meterRegistry);
            return newMeterValue;
        });
    }

    private Integer circuitStateToValue(QueueCircuitState queueCircuitState) {
        if (queueCircuitState == null) {
            return null;
        }
        switch (queueCircuitState) {
            case CLOSED:
                return 0;
            case HALF_OPEN:
                return 1;
            case OPEN:
                return 2;
            default:
                return null;
        }
    }
}

package org.swisspush.gateleen.queue.queuing.circuitbreaker.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.lock.Lock;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.QueueCircuitBreakerStorage;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.swisspush.gateleen.core.exception.GateleenExceptionFactory.newGateleenWastefulExceptionFactory;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.impl.RedisQueueCircuitBreakerStorage.*;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.monitoring.QueueCircuitBreakerMetricsCollector.*;

/**
 * Tests for the {@link QueueCircuitBreakerMetricsCollector} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class QueueCircuitBreakerMetricsCollectorTest {

    private Vertx vertx;
    private Lock lock;
    private QueueCircuitBreakerStorage queueCircuitBreakerStorage;
    private MeterRegistry meterRegistry;
    private QueueCircuitBreakerMetricsCollector collector;

    @org.junit.Rule
    public Timeout rule = Timeout.seconds(50);

    @Before
    public void setUp() {
        vertx = Vertx.vertx();

        lock = Mockito.mock(Lock.class);
        Mockito.when(lock.acquireLock(anyString(), anyString(), anyLong())).thenReturn(Future.succeededFuture(Boolean.TRUE));
        Mockito.when(lock.releaseLock(anyString(), anyString())).thenReturn(Future.succeededFuture(Boolean.TRUE));

        meterRegistry = new SimpleMeterRegistry();
        queueCircuitBreakerStorage = Mockito.mock(QueueCircuitBreakerStorage.class);

        collector = new QueueCircuitBreakerMetricsCollector(vertx, lock, queueCircuitBreakerStorage, meterRegistry,
                newGateleenWastefulExceptionFactory(), 5);
    }

    @Test
    public void testCollectMetricsSuccess(TestContext context) {
        Async async = context.async();

        JsonObject allCircuits = new JsonObject();
        allCircuits.put("5645745t43f54gf", createCircuitInfo("closed", "M1", 0));
        allCircuits.put("12rt878665f54gf", createCircuitInfo("half_open", "M2", 35));
        allCircuits.put("8789jz45745t43f54gf", createCircuitInfo("open", "M3", 100));

        Mockito.when(queueCircuitBreakerStorage.getAllCircuits())
                .thenReturn(Future.succeededFuture(allCircuits));

        collector.collectMetrics().onComplete(event -> {
            context.assertTrue(event.succeeded());

            // verify status gauges
            context.assertEquals(0.0, getStatusGauge("M1").value(), "Status of circuit M1 should be 0.0 -> CLOSED");
            context.assertEquals(1.0, getStatusGauge("M2").value(), "Status of circuit M2 should be 0.0 -> HALF_OPEN");
            context.assertEquals(2.0, getStatusGauge("M3").value(), "Status of circuit M3 should be 0.0 -> OPEN");

            // verify fail ratio gauges
            context.assertEquals(0.0, getFailRatioGauge("M1").value(), "Fail ratio of circuit M1 should be 0.0");
            context.assertEquals(35.0, getFailRatioGauge("M2").value(), "Fail ratio of circuit M2 should be 35.0");
            context.assertEquals(100.0, getFailRatioGauge("M3").value(), "Fail ratio of circuit M3 should be 100.0");

            verify(lock, Mockito.times(1)).releaseLock(eq(COLLECT_METRICS_TASK_LOCK), anyString());

            async.complete();
        });
    }

    @Test
    public void testCollectMetricsStorageFailure(TestContext context) {
        Async async = context.async();

        JsonObject allCircuits = new JsonObject();
        allCircuits.put("5645745t43f54gf", createCircuitInfo("closed", "M1", 0));
        allCircuits.put("12rt878665f54gf", createCircuitInfo("half_open", "M2", 35));
        allCircuits.put("8789jz45745t43f54gf", createCircuitInfo("open", "M3", 100));

        Mockito.when(queueCircuitBreakerStorage.getAllCircuits())
                .thenReturn(Future.failedFuture("Boooom"));

        collector.collectMetrics().onComplete(event -> {
            context.assertTrue(event.failed());

            verify(lock, Mockito.times(1)).releaseLock(eq(COLLECT_METRICS_TASK_LOCK), anyString());

            async.complete();
        });
    }

    @Test
    public void testCollectMetricsIgnoreEntries(TestContext context) {
        Async async = context.async();

        JsonObject allCircuits = new JsonObject();
        allCircuits.put("5645745t43f5465", createCircuitInfo("closed", "M1", 0));
        allCircuits.put("12rt878665f54gf", createCircuitInfo("foobar_state", "M2", 35));
        allCircuits.put("8789jz45745t4g8", createCircuitInfo(null, "M3", 100));
        allCircuits.put("8634662437g894c", createCircuitInfo("open", null, 100));

        Mockito.when(queueCircuitBreakerStorage.getAllCircuits())
                .thenReturn(Future.succeededFuture(allCircuits));

        collector.collectMetrics().onComplete(event -> {
            context.assertTrue(event.succeeded());

            // verify status gauges
            context.assertEquals(0.0, getStatusGauge("M1").value(), "Status of circuit M1 should be 0.0 -> CLOSED");
            context.assertFalse(statusGaugeExists("M2"));
            context.assertFalse(statusGaugeExists("M3"));
            context.assertFalse(statusGaugeExists("M4"));

            // verify fail ratio gauges
            context.assertEquals(0.0, getFailRatioGauge("M1").value(), "Fail ratio of circuit M1 should be 0.0");
            context.assertFalse(failRatioGaugeExists("M2"));
            context.assertFalse(failRatioGaugeExists("M3"));
            context.assertFalse(failRatioGaugeExists("M4"));

            verify(lock, Mockito.times(1)).releaseLock(eq(COLLECT_METRICS_TASK_LOCK), anyString());

            async.complete();
        });
    }

    public boolean gaugeExists(String metricName) {
        meterRegistry.get(CIRCUIT_BREAKER_STATUS_METRIC).tag("metricName", metricName).gauge();
        return true;
    }

    @Test
    public void testCollectMetricsFailedToAcquireLock(TestContext context) {
        Async async = context.async();

        Mockito.when(lock.acquireLock(anyString(), anyString(), anyLong())).thenReturn(Future.failedFuture("Boooom"));

        collector.collectMetrics().onComplete(event -> {
            context.assertTrue(event.failed());
            context.assertEquals("Boooom", event.cause().getMessage());
            Mockito.verifyNoInteractions(queueCircuitBreakerStorage);
            async.complete();
        });
    }

    @Test
    public void testCollectMetricsLockAlreadyAcquired(TestContext context) {
        Async async = context.async();

        Mockito.when(lock.acquireLock(anyString(), anyString(), anyLong())).thenReturn(Future.succeededFuture(Boolean.FALSE));

        collector.collectMetrics().onComplete(event -> {
            context.assertTrue(event.succeeded());
            Mockito.verifyNoInteractions(queueCircuitBreakerStorage);
            async.complete();
        });
    }

    private Gauge getStatusGauge(String metricName){
        return meterRegistry.get(CIRCUIT_BREAKER_STATUS_METRIC).tag("metricName", metricName).gauge();
    }

    private boolean statusGaugeExists(String metricName){
        try {
            meterRegistry.get(CIRCUIT_BREAKER_STATUS_METRIC).tag("metricName", metricName).gauge();
            return true;
        } catch (MeterNotFoundException ex) {
            return false;
        }
    }

    private Gauge getFailRatioGauge(String metricName){
        return meterRegistry.get(CIRCUIT_BREAKER_FAILRATIO_METRIC).tag("metricName", metricName).gauge();
    }

    private boolean failRatioGaugeExists(String metricName){
        try {
            meterRegistry.get(CIRCUIT_BREAKER_FAILRATIO_METRIC).tag("metricName", metricName).gauge();
            return true;
        } catch (MeterNotFoundException ex) {
            return false;
        }
    }

    private JsonObject createCircuitInfo(String status, String metricName, Integer failRatio){
        JsonObject circuit = new JsonObject();
        JsonObject infos = new JsonObject().put(FIELD_CIRCUIT, "/some/circuit/url");
        circuit.put("infos", infos);

        if(status != null) {
            circuit.put(FIELD_STATUS, status);
        }
        if(metricName != null) {
            infos.put(FIELD_METRICNAME, metricName);
        }
        if(failRatio != null) {
            infos.put(FIELD_FAILRATIO, failRatio);
        }

        return circuit;
    }
}

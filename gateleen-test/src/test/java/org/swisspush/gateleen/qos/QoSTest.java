package org.swisspush.gateleen.qos;

import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;
import com.google.common.collect.ImmutableMap;
import com.jayway.restassured.RestAssured;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import javax.management.*;

import static com.jayway.restassured.RestAssured.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;

/**
 * Test class for the QoS Feature.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
@RunWith(VertxUnitRunner.class)
public class QoSTest extends AbstractTest {
    /*
     * this is needed for faking
     * the load data (response times)
     */
    private MBeanServer mbeanServer;

    private ObjectName sentinelA;
    private ObjectName sentinelB;
    private ObjectName sentinelC;
    private ObjectName sentinelD;
    private ObjectName sentinelE;

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath(ROOT + "/");
    }

    /**
     * Init
     * 
     * @throws MalformedObjectNameException
     */
    private void init() throws MalformedObjectNameException {
        sentinelA = new ObjectName(getObjectName("sentinelA"));
        sentinelB = new ObjectName(getObjectName("sentinelB"));
        sentinelC = new ObjectName(getObjectName("sentinelC"));
        sentinelD = new ObjectName(getObjectName("sentinelD"));
        sentinelE = new ObjectName(getObjectName("sentinelE"));

        // override current MBeanServer for QoS
        mbeanServer = Mockito.mock(MBeanServer.class);
        when(mbeanServer.isRegistered(any(ObjectName.class))).thenReturn(Boolean.FALSE);
        qosHandler.setMBeanServer(mbeanServer);

        // reset everything
        delete();

        // add a routing
        JsonObject rule = TestUtils.createRoutingRule(ImmutableMap.of(
                "metricName",
                "mainStorage",
                "path",
                "/$1",
                "storage",
                "main",
                "logExpiry",
                0));
        JsonObject rules = TestUtils.addRoutingRule(new JsonObject(), "/(.*)", rule);

        // sentinel 1
        rule = TestUtils.createRoutingRule(ImmutableMap.of(
                "description", "sentinel 1",
                "metricName", "sentinelA",
                "url", "http://localhost:" + MAIN_PORT + "/$1"));
        rules = TestUtils.addRoutingRule(rules, "/playground/test/sapi1/(.*)", rule);

        // sentinel 2
        rule = TestUtils.createRoutingRule(ImmutableMap.of(
                "description", "sentinel 2",
                "metricName", "sentinelB",
                "url", "http://localhost:" + MAIN_PORT + "/$1"));
        rules = TestUtils.addRoutingRule(rules, "/playground/test/sapi2/(.*)", rule);

        // sentinel 3
        rule = TestUtils.createRoutingRule(ImmutableMap.of(
                "description", "sentinel 3",
                "metricName", "sentinelC",
                "url", "http://localhost:" + MAIN_PORT + "/$1"));
        rules = TestUtils.addRoutingRule(rules, "/playground/test/sapi3/(.*)", rule);

        // sentinel 4
        rule = TestUtils.createRoutingRule(ImmutableMap.of(
                "description", "sentinel 4",
                "metricName", "sentinelD",
                "url", "http://localhost:" + MAIN_PORT + "/$1"));
        rules = TestUtils.addRoutingRule(rules, "/playground/test/sapi4/(.*)", rule);

        // PUT rules
        TestUtils.putRoutingRules(rules);

        // PUT QoS rules
        createQoSRules();

        // wait some time and then cancel the timer
        TestUtils.waitSomeTime(7);
        qosHandler.cancelTimer();
        TestUtils.waitSomeTime(7);
    }

    /**
     * Creates a set of QoS rules for the
     * tests.
     */
    private void createQoSRules() {
        JsonObject settings = new JsonObject();
        JsonObject config = new JsonObject();
        JsonObject sentinels = new JsonObject();
        JsonObject rules = new JsonObject();

        // config
        config.put("percentile", 75);
        config.put("quorum", 40);
        config.put("period", 3);
        config.put("minSampleCount", 1000);
        config.put("minSentinelCount", 5);
        settings.put("config", config);

        // sentinels
        JsonObject sentinel1 = new JsonObject();
        sentinel1.put("percentile", 50);
        JsonObject sentinel2 = new JsonObject();
        JsonObject sentinel3 = new JsonObject();
        JsonObject sentinel4 = new JsonObject();
        JsonObject sentinel5 = new JsonObject();
        sentinels.put("sentinelA", sentinel1);
        sentinels.put("sentinelB", sentinel2);
        sentinels.put("sentinelC", sentinel3);
        sentinels.put("sentinelD", sentinel4);
        sentinels.put("sentinelE", sentinel5);
        settings.put("sentinels", sentinels);

        // rules
        JsonObject rule1 = new JsonObject();
        rule1.put("reject", 1.3);
        rule1.put("warn", 1.1);
        rules.put("/playground/myapi1/v1/.*", rule1);

        JsonObject rule2 = new JsonObject();
        rule2.put("reject", 1.7);
        rules.put("/playground/myapi2/v1/.*", rule2);

        settings.put("rules", rules);

        // PUT the QoS rules
        given().body(settings.toString()).put("server/admin/v1/qos").then().assertThat().statusCode(200);
    }

    /**
     * We will simulate a normal load (loading fake data
     * to the mbean server).
     */
    @Test
    public void simulateNormalLoad(TestContext context) {
        Async async = context.async();
        try {
            init();
            loadSimulatedNormalData();

            put("myapi1/v1/test").then().assertThat().statusCode(200);
            put("myapi2/v1/test").then().assertThat().statusCode(200);
        } catch (Exception failed) {
            failed.printStackTrace();
            context.fail("Exception: " + failed.getMessage());
        }

        System.out.println("Done");

        async.complete();
    }

    /**
     * We will simulate a mixed load (loading fake data
     * to the mbean server).
     */
    @Test
    public void simulateMixedLoad(TestContext context) {
        Async async = context.async();
        try {
            init();

            // loading mixed data
            loadSimulatedMixedData();

            /*
             * R1:
             * Reject: 2.1164 (rejected)
             * Warn: 1.628 (warned)
             * R2:
             * Reject: 2.516 (not rejected)
             */

            put("myapi1/v1/test").then().assertThat().statusCode(503);
            put("myapi2/v1/test").then().assertThat().statusCode(200);

            for (QoSRule rule : qosHandler.getQosRules()) {
                if ("myapi1/v1/test".matches(rule.getUrlPattern().pattern())) {
                    context.assertTrue(rule.getActions().contains(QoSHandler.WARN_ACTION));
                    context.assertTrue(rule.getActions().contains(QoSHandler.REJECT_ACTION));
                    break;
                }
            }

            // load normal Data
            loadSimulatedNormalData();

            put("myapi1/v1/test").then().assertThat().statusCode(200);
            put("myapi2/v1/test").then().assertThat().statusCode(200);

            for (QoSRule rule : qosHandler.getQosRules()) {
                context.assertFalse(rule.performAction());
            }
        } catch (Exception failed) {
            context.fail("Exception: " + failed.getMessage());
        }

        System.out.println("Done");

        async.complete();
    }

    /**
     * We will simulate a heavy load (loading fake data
     * to the mbean server).
     */
    @Test
    public void simulateHeavyLoad(TestContext context) {
        Async async = context.async();
        try {
            init();

            // loading mixed data
            loadSimulatedHeavyData();

            put("myapi1/v1/test").then().assertThat().statusCode(503);
            put("myapi2/v1/test").then().assertThat().statusCode(503);

            qosHandler.getQosRules().stream().filter(rule -> "myapi1/v1/test".matches(rule.getUrlPattern().pattern())).forEach(rule -> {
                context.assertTrue(rule.getActions().contains(QoSHandler.WARN_ACTION));
                context.assertTrue(rule.getActions().contains(QoSHandler.REJECT_ACTION));
            });

            // load normal Data
            loadSimulatedNormalData();
            TestUtils.waitSomeTime(6);

            put("myapi1/v1/test").then().assertThat().statusCode(200);
            put("myapi2/v1/test").then().assertThat().statusCode(200);

            for (QoSRule rule : qosHandler.getQosRules()) {
                context.assertFalse(rule.performAction());
            }
        } catch (Exception failed) {
            context.fail("Exception: " + failed.getMessage());
        }

        System.out.println("Done");

        async.complete();
    }

    /**
     * Creates a set of normal sentinel data.
     * 
     * @throws Exception
     */
    private void loadSimulatedNormalData() throws Exception {
        setLowestMeasuredPercentile(1.0);

        setSampleCount(sentinelA, 1000);
        setSampleCount(sentinelB, 1000);
        setSampleCount(sentinelC, 1000);
        setSampleCount(sentinelD, 1000);
        setSampleCount(sentinelE, 1000);

        when(mbeanServer.getAttribute(sentinelA, "50thPercentile")).thenReturn(1.0);
        when(mbeanServer.getAttribute(sentinelB, "75thPercentile")).thenReturn(1.0);
        when(mbeanServer.getAttribute(sentinelC, "75thPercentile")).thenReturn(1.0);
        when(mbeanServer.getAttribute(sentinelD, "75thPercentile")).thenReturn(1.0);
        when(mbeanServer.getAttribute(sentinelE, "75thPercentile")).thenReturn(1.0);

        when(mbeanServer.isRegistered(sentinelA)).thenReturn(Boolean.TRUE);
        when(mbeanServer.isRegistered(sentinelB)).thenReturn(Boolean.TRUE);
        when(mbeanServer.isRegistered(sentinelC)).thenReturn(Boolean.TRUE);
        when(mbeanServer.isRegistered(sentinelD)).thenReturn(Boolean.TRUE);
        when(mbeanServer.isRegistered(sentinelE)).thenReturn(Boolean.TRUE);

        System.out.println("Start manual evaluation ...");
        qosHandler.evaluateQoSActions();
    }

    /**
     * Set the given value as the lowest percentile value for all
     * sentinels.
     * 
     * @param lowestPercentileValue
     */
    private void setLowestMeasuredPercentile(double lowestPercentileValue) {
        for (QoSSentinel sentinel : qosHandler.getQosSentinels()) {
            sentinel.setLowestPercentileValue(lowestPercentileValue);
        }
    }

    /**
     * Creates a set of mixed sentinel data.
     * Average response time: 1.48
     * 
     * @throws Exception
     */
    private void loadSimulatedMixedData() throws Exception {
        setLowestMeasuredPercentile(1.0);

        setSampleCount(sentinelA, 1000);
        setSampleCount(sentinelB, 1000);
        setSampleCount(sentinelC, 1000);
        setSampleCount(sentinelD, 1000);
        setSampleCount(sentinelE, 1000);

        when(mbeanServer.getAttribute(sentinelA, "50thPercentile")).thenReturn(1.0);
        when(mbeanServer.getAttribute(sentinelB, "75thPercentile")).thenReturn(1.1);
        when(mbeanServer.getAttribute(sentinelC, "75thPercentile")).thenReturn(1.1);
        when(mbeanServer.getAttribute(sentinelD, "75thPercentile")).thenReturn(1.6);
        when(mbeanServer.getAttribute(sentinelE, "75thPercentile")).thenReturn(2.1);

        when(mbeanServer.isRegistered(sentinelA)).thenReturn(Boolean.TRUE);
        when(mbeanServer.isRegistered(sentinelB)).thenReturn(Boolean.TRUE);
        when(mbeanServer.isRegistered(sentinelC)).thenReturn(Boolean.TRUE);
        when(mbeanServer.isRegistered(sentinelD)).thenReturn(Boolean.TRUE);
        when(mbeanServer.isRegistered(sentinelE)).thenReturn(Boolean.TRUE);

        System.out.println("Start manual evaluation ...");
        qosHandler.evaluateQoSActions();
    }

    /**
     * Creates a set of heavy sentinel data.
     * Average response time: 2.42
     * 
     * @throws Exception
     */
    private void loadSimulatedHeavyData() throws Exception {
        setLowestMeasuredPercentile(1.0);

        setSampleCount(sentinelA, 1000);
        setSampleCount(sentinelB, 1000);
        setSampleCount(sentinelC, 1000);
        setSampleCount(sentinelD, 1000);
        setSampleCount(sentinelE, 1000);

        when(mbeanServer.getAttribute(sentinelA, "50thPercentile")).thenReturn(0.1);
        when(mbeanServer.getAttribute(sentinelB, "75thPercentile")).thenReturn(0.1);
        when(mbeanServer.getAttribute(sentinelC, "75thPercentile")).thenReturn(3.3);
        when(mbeanServer.getAttribute(sentinelD, "75thPercentile")).thenReturn(4.3);
        when(mbeanServer.getAttribute(sentinelE, "75thPercentile")).thenReturn(4.3);

        when(mbeanServer.isRegistered(sentinelA)).thenReturn(Boolean.TRUE);
        when(mbeanServer.isRegistered(sentinelB)).thenReturn(Boolean.TRUE);
        when(mbeanServer.isRegistered(sentinelC)).thenReturn(Boolean.TRUE);
        when(mbeanServer.isRegistered(sentinelD)).thenReturn(Boolean.TRUE);
        when(mbeanServer.isRegistered(sentinelE)).thenReturn(Boolean.TRUE);

        System.out.println("Start manual evaluation ...");
        qosHandler.evaluateQoSActions();
    }

    /**
     * Sets the sample count of the given sentinel
     * 
     * @param sampleCount
     * @throws ReflectionException
     * @throws MBeanException
     * @throws InstanceNotFoundException
     * @throws AttributeNotFoundException
     */
    private void setSampleCount(ObjectName sentinel, int sampleCount) throws AttributeNotFoundException, InstanceNotFoundException, MBeanException, ReflectionException {
        when(mbeanServer.getAttribute(sentinel, "Count")).thenReturn(sampleCount);
    }

    /**
     * Returns the correct jmx name for the ObjectName
     * object.
     * 
     * @param sentinel name of sentinel
     * @return the name for the jmx object
     */
    private String getObjectName(String sentinel) {
        return "metrics:name=" + PREFIX + "routing." + sentinel + ".duration";
    }

}

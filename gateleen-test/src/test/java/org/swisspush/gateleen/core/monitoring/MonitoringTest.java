package org.swisspush.gateleen.core.monitoring;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.Serializable;
import java.lang.management.ManagementFactory;

import static com.jayway.restassured.RestAssured.*;

/**
 * Tests that the monitoring of the duration of storage requests does not
 * exceeds a specific limit.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
@RunWith(VertxUnitRunner.class)
public class MonitoringTest extends AbstractTest {
    Logger log = LoggerFactory.getLogger(MonitoringTest.class);

    private static final long MAX_DURATION_THRESHOLD = 100;
    private static final double MEAN_DURATION_THRESHOLD = 25.0;

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath(SERVER_ROOT + "/tests");
    }

    @Test
    public void testStorageMetrics(TestContext context) throws Exception {
        Async async = context.async();
        // cleanup
        delete();

        // 1000000 ns = 1ms
        // duration is in ms (see MonitoringHandler.stopRequestMetricTracking(...))

        String metricName = "mainstorage";

        // add a routing
        JsonObject storageRule = TestUtils.createRoutingRule(ImmutableMap.<String, Serializable>builder()
                .put("description", "a routing for the metric tests")
                .put("metricName", metricName)
                .put("path", "/$1")
                .put("storage", "main")
                .put("logExpiry", 0)
                .build());
        JsonObject rules = TestUtils.addRoutingRule(new JsonObject(), "/(.*)", storageRule);
        TestUtils.putRoutingRules(rules);

        // get the MBeanServer
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();

        // the bean we want to check
        String beanName = "metrics:name=" + PREFIX + "routing." + metricName + ".duration";
        ObjectName beanNameObject = new ObjectName(beanName);

        // do a few slow requests
        for (int count = 0; count < 10; count++) {
            given().body("{ \"name\" : \"test" + count + "\" }").put("res" + count).then().assertThat().statusCode(200);
            TestUtils.waitSomeTime(1);
            get("res" + count).then().assertThat().statusCode(200);
            TestUtils.waitSomeTime(1);
        }

        // do a few quick requests
        for (int count = 0; count < 10; count++) {
            delete("res" + count);
            given().body("{ \"name\" : \"test" + count + "\" }").put("res" + count).then().assertThat().statusCode(200);
            get("res" + count).then().assertThat().statusCode(200);
        }

        // do another 1000 requests
        for (int count = 0; count < 1000; count++) {
            given().body("{ \"name\" : \"test" + count + "\" }").put("newres" + count).then().assertThat().statusCode(200);
        }

        // wait a sec
        TestUtils.waitSomeTime(1);

        // read the jmx infos
        if (mbs.isRegistered(beanNameObject)) {
            log.info(" > Min:    {}", mbs.getAttribute(beanNameObject, "Min"));
            log.info(" > Mean:   {}", mbs.getAttribute(beanNameObject, "Mean"));
            log.info(" > Max:    {}", mbs.getAttribute(beanNameObject, "Max"));
            log.info(" > Count:  {}", mbs.getAttribute(beanNameObject, "Count"));
            log.info(" > StdDev: {}", mbs.getAttribute(beanNameObject, "StdDev"));

            // check max threshold
            context.assertTrue((Long) mbs.getAttribute(beanNameObject, "Max") <= MAX_DURATION_THRESHOLD, "'Max' should be below the threshold");

            // checm mean threshold
            context.assertTrue((Double) mbs.getAttribute(beanNameObject, "Mean") <= MEAN_DURATION_THRESHOLD, "'Mean' should be below the threshold");
        } else {
            context.fail("could not found mbean " + beanName);
        }

        async.complete();
    }
}

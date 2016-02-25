package org.swisspush.gateleen.core.control;

import io.vertx.core.Vertx;

import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

/**
 * Controller for the Reset Metrics mechanism. Registers the Reset Metrics MBean
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class ResetMetricsController {

    private static final String RESET_METRICS_MBEAN_NAME = ":type=ResetMetricsController";

    private Vertx vertx;

    public ResetMetricsController(Vertx vertx){
        this.vertx = vertx;
    }

    public void registerResetMetricsControlMBean(String domain, String prefix) {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();

        ResetMetrics resetMetrics = new ResetMetrics(vertx, prefix);

        ObjectName name;
        try {
            name = new ObjectName(domain+RESET_METRICS_MBEAN_NAME);
        } catch (MalformedObjectNameException e) {
            throw new RuntimeException(e);
        }

        try {
            mbs.unregisterMBean(name);
        } catch(Exception e) {
            // Ignore it, perhaps this is the first time
        }

        try {
            mbs.registerMBean(resetMetrics, name);
        } catch (Exception e) {
            throw new RuntimeException("exception while registering the ResetMetricsController MBean", e);
        }
    }
}

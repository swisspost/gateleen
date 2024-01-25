package org.swisspush.gateleen.monitoring;

import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.Address;

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

    private static Logger log = LoggerFactory.getLogger(ResetMetricsController.class);
    private static final String RESET_METRICS_MBEAN_NAME = ":type=ResetMetricsController";

    private Vertx vertx;
    private String monitoringAddress;

    public ResetMetricsController(Vertx vertx){ this(vertx, Address.monitoringAddress()); }

    public ResetMetricsController(Vertx vertx, String monitoringAddress){
        this.vertx = vertx;
        this.monitoringAddress = monitoringAddress;
    }

    public void registerResetMetricsControlMBean(String domain, String prefix) {
        log.debug("About to register ResetMetricsControlMBean with domain '{}', prefix '{}' and monitoring address '{}'", domain, prefix, monitoringAddress);
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        ResetMetrics resetMetrics = new ResetMetrics(vertx, prefix, monitoringAddress);
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

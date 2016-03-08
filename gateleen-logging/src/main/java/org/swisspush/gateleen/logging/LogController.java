package org.swisspush.gateleen.logging;

import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

public class LogController {


    public void registerLogConfiguratorMBean(String domain) {
        System.setProperty("org.swisspush.gateleen.logging.log4j.showall", "true");
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();

        ObjectName name;
        try {
            name = new ObjectName(domain+":type=LogConfigurator");
        } catch (MalformedObjectNameException e) {
            throw new RuntimeException(e);
        }

        try {
            mbs.unregisterMBean(name);
        } catch(Exception e) {
            // Ignore it, perhaps this is the first time
        }

        try {
            mbs.registerMBean(new Log4jConfiguratorMBean(), name);
        } catch (Exception e) {
            throw new RuntimeException("exception while registering the LogController MBean", e);
        }
    }

}

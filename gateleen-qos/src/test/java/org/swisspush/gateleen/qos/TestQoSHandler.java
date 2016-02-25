package org.swisspush.gateleen.qos;

import org.swisspush.gateleen.core.storage.ResourceStorage;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;

/**
 * Test class for the QoSHandler.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class TestQoSHandler {
    private Vertx vertx;
    private String prefix;
    private String qosSettingsPath;
    private ResourceStorage storage;
    private MBeanServer mbeanServer;

    @Before
    public void init() {
        storage = Mockito.mock(ResourceStorage.class);
        vertx = Mockito.mock(Vertx.class);
        Mockito.when(vertx.eventBus()).thenReturn(Mockito.mock(EventBus.class));

        mbeanServer = Mockito.mock(MBeanServer.class);
        when(mbeanServer.isRegistered(any(ObjectName.class))).thenReturn(Boolean.TRUE);

        prefix = "test";
        qosSettingsPath = "/test/server/admin/v1/qos";
    }

    /**
     * Tests if an action is applied or not if the given
     * values exceeds the threashold (or not).
     */
    @Test
    public void testActionDetermination() {
        QoSHandler qosHandler = new QoSHandler(vertx, storage, qosSettingsPath, new HashMap<>(), prefix);

        // ratio, threshold ratio
        assertThat(qosHandler.actionNecessary(1.5, 1.6), is(Boolean.TRUE));
        assertThat(qosHandler.actionNecessary(1.5, 1.5), is(Boolean.TRUE));
        assertThat(qosHandler.actionNecessary(1.5, 2.0), is(Boolean.TRUE));

        assertThat(qosHandler.actionNecessary(1.5, 1.45), is(Boolean.FALSE));
        assertThat(qosHandler.actionNecessary(1.5, 1.3), is(Boolean.FALSE));
        assertThat(qosHandler.actionNecessary(1.5, 1), is(Boolean.FALSE));
    }

    /**
     * Test basic behaviour (warn, reject) under normal load.
     * 
     * @throws Exception
     */
    @Test
    public void testCalculation_normalLoad() throws Exception {
        QoSHandler qosHandler = new QoSHandler(vertx, storage, qosSettingsPath, new HashMap<>(), prefix);
        qosHandler.setMBeanServer(mbeanServer);

        ObjectName sentinel1 = new ObjectName(getObjectName("sentinel1"));
        ObjectName sentinel2 = new ObjectName(getObjectName("sentinel2"));
        ObjectName sentinel3 = new ObjectName(getObjectName("sentinel3"));
        ObjectName sentinel4 = new ObjectName(getObjectName("sentinel4"));
        ObjectName sentinel5 = new ObjectName(getObjectName("sentinel5"));

        when(mbeanServer.getAttribute(sentinel1, "75thPercentile")).thenReturn(1.5);
        when(mbeanServer.getAttribute(sentinel2, "75thPercentile")).thenReturn(1.5);
        when(mbeanServer.getAttribute(sentinel3, "75thPercentile")).thenReturn(1.5);
        when(mbeanServer.getAttribute(sentinel4, "75thPercentile")).thenReturn(1.5);
        when(mbeanServer.getAttribute(sentinel5, "75thPercentile")).thenReturn(1.5);

        when(mbeanServer.getAttribute(sentinel1, "Count")).thenReturn(1);
        when(mbeanServer.getAttribute(sentinel2, "Count")).thenReturn(1);
        when(mbeanServer.getAttribute(sentinel3, "Count")).thenReturn(1);
        when(mbeanServer.getAttribute(sentinel4, "Count")).thenReturn(1);
        when(mbeanServer.getAttribute(sentinel5, "Count")).thenReturn(1);

        QoSConfig config = new QoSConfig(75, 40, 5, 0, 0);
        qosHandler.setGlobalQoSConfig(config);

        List<QoSSentinel> sentinels = new ArrayList<>();
        sentinels.add(new QoSSentinel("sentinel1"));
        sentinels.add(new QoSSentinel("sentinel2"));
        sentinels.add(new QoSSentinel("sentinel3"));
        sentinels.add(new QoSSentinel("sentinel4"));
        sentinels.add(new QoSSentinel("sentinel5"));
        qosHandler.setQosSentinels(sentinels);

        List<QoSRule> rules = new ArrayList<>();

        /*
         * no action expected
         */
        QoSRule rule1 = new QoSRule(Pattern.compile("/test1/*"));
        rule1.setReject(1.5);
        rules.add(rule1);

        /*
         * reject action expected
         */
        QoSRule rule2 = new QoSRule(Pattern.compile("/test2/*"));
        rule2.setReject(0.5);
        rules.add(rule2);

        /*
         * warn action expected
         */
        QoSRule rule3 = new QoSRule(Pattern.compile("/test3/*"));
        rule3.setWarn(0.5);
        rules.add(rule3);

        /*
         * reject action expected
         * warn action not expected
         */
        QoSRule rule4 = new QoSRule(Pattern.compile("/test4/*"));
        rule4.setReject(0.5);
        rule4.setWarn(1.5);
        rules.add(rule4);

        /*
         * reject action not expected
         * warn action expected
         */
        QoSRule rule5 = new QoSRule(Pattern.compile("/test5/*"));
        rule5.setReject(1.5);
        rule5.setWarn(0.5);
        rules.add(rule5);

        /*
         * reject action expected
         * warn action expected
         */
        QoSRule rule6 = new QoSRule(Pattern.compile("/test6/*"));
        rule6.setReject(0.5);
        rule6.setWarn(0.5);
        rules.add(rule6);

        /*
         * reject action not expected
         * warn action not expected
         */
        QoSRule rule7 = new QoSRule(Pattern.compile("/test7/*"));
        rule7.setReject(1.5);
        rule7.setWarn(1.5);
        rules.add(rule7);

        qosHandler.setQosRules(rules);

        // perform the calculation
        qosHandler.evaluateQoSActions();

        // check the result

        // perform action?
        assertThat(rule1.performAction(), is(Boolean.FALSE));
        assertThat(rule2.performAction(), is(Boolean.TRUE));
        assertThat(rule3.performAction(), is(Boolean.TRUE));
        assertThat(rule4.performAction(), is(Boolean.TRUE));
        assertThat(rule5.performAction(), is(Boolean.TRUE));
        assertThat(rule6.performAction(), is(Boolean.TRUE));
        assertThat(rule7.performAction(), is(Boolean.FALSE));

        // correct action count?
        assertThat(rule1.getActions().size(), is(0));
        assertThat(rule2.getActions().size(), is(1));
        assertThat(rule3.getActions().size(), is(1));
        assertThat(rule4.getActions().size(), is(1));
        assertThat(rule5.getActions().size(), is(1));
        assertThat(rule6.getActions().size(), is(2));
        assertThat(rule7.getActions().size(), is(0));

        // correct action?
        assertThat(rule2.getActions().get(0), is(QoSHandler.REJECT_ACTION));
        assertThat(rule3.getActions().get(0), is(QoSHandler.WARN_ACTION));
        assertThat(rule4.getActions().get(0), is(QoSHandler.REJECT_ACTION));
        assertThat(rule5.getActions().get(0), is(QoSHandler.WARN_ACTION));
        assertThat(rule6.getActions(), hasItems(QoSHandler.WARN_ACTION, QoSHandler.REJECT_ACTION));

        // -----
    }

    /**
     * Test reject behaviour under mixed load.
     * 
     * @throws Exception
     */
    @Test
    public void testCalculation_mixedLoad() throws Exception {
        QoSHandler qosHandler = new QoSHandler(vertx, storage, qosSettingsPath, new HashMap<>(), prefix);
        qosHandler.setMBeanServer(mbeanServer);

        ObjectName sentinel1 = new ObjectName(getObjectName("sentinel1"));
        ObjectName sentinel2 = new ObjectName(getObjectName("sentinel2"));
        ObjectName sentinel3 = new ObjectName(getObjectName("sentinel3"));
        ObjectName sentinel4 = new ObjectName(getObjectName("sentinel4"));
        ObjectName sentinel5 = new ObjectName(getObjectName("sentinel5"));

        when(mbeanServer.getAttribute(sentinel1, "75thPercentile")).thenReturn(0.5);
        when(mbeanServer.getAttribute(sentinel2, "75thPercentile")).thenReturn(1d);
        when(mbeanServer.getAttribute(sentinel3, "75thPercentile")).thenReturn(1d);
        when(mbeanServer.getAttribute(sentinel4, "75thPercentile")).thenReturn(1.5);
        when(mbeanServer.getAttribute(sentinel5, "75thPercentile")).thenReturn(2.5);

        when(mbeanServer.getAttribute(sentinel1, "Count")).thenReturn(1);
        when(mbeanServer.getAttribute(sentinel2, "Count")).thenReturn(1);
        when(mbeanServer.getAttribute(sentinel3, "Count")).thenReturn(1);
        when(mbeanServer.getAttribute(sentinel4, "Count")).thenReturn(1);
        when(mbeanServer.getAttribute(sentinel5, "Count")).thenReturn(1);

        // => average response time = 1.3, 40% index = 2 (value = 1.5)
        QoSConfig config = new QoSConfig(75, 40, 5, 0, 0);
        qosHandler.setGlobalQoSConfig(config);

        List<QoSSentinel> sentinels = new ArrayList<>();
        sentinels.add(new QoSSentinel("sentinel1"));
        sentinels.add(new QoSSentinel("sentinel2"));
        sentinels.add(new QoSSentinel("sentinel3"));
        sentinels.add(new QoSSentinel("sentinel4"));
        sentinels.add(new QoSSentinel("sentinel5"));
        qosHandler.setQosSentinels(sentinels);

        List<QoSRule> rules = new ArrayList<>();

        /*
         * reject expected
         */
        QoSRule rule1 = new QoSRule(Pattern.compile("/test1/*"));
        rule1.setReject(0.9);
        rules.add(rule1);

        /*
         * reject not expected
         */
        QoSRule rule2 = new QoSRule(Pattern.compile("/test2/*"));
        rule2.setReject(1.5);
        rules.add(rule2);

        /*
         * reject expected
         */
        QoSRule rule3 = new QoSRule(Pattern.compile("/test3/*"));
        rule3.setReject(0.5);
        rules.add(rule3);

        qosHandler.setQosRules(rules);

        // perform the calculation
        qosHandler.evaluateQoSActions();

        // check the result

        // result
        assertThat(rule1.performAction(), is(Boolean.TRUE));
        assertThat(rule1.getActions().get(0), is(QoSHandler.REJECT_ACTION));

        assertThat(rule2.performAction(), is(Boolean.FALSE));
        assertThat(rule2.getActions().isEmpty(), is(Boolean.TRUE));

        assertThat(rule3.performAction(), is(Boolean.TRUE));
        assertThat(rule3.getActions().get(0), is(QoSHandler.REJECT_ACTION));

        // -----
    }

    /**
     * Returns the correct jmx name for the ObjectName
     * object.
     * 
     * @param sentinel name of sentinel
     * @return the name for the jmx object
     */
    private String getObjectName(String sentinel) {
        return "metrics:name=" + prefix + "routing." + sentinel + ".duration";
    }
}

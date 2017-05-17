package org.swisspush.gateleen.qos;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.http.DummyHttpServerResponse;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.StatusCode;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for the {@link QoSHandler} class
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
@RunWith(VertxUnitRunner.class)
public class QoSHandlerTest {
    private Vertx vertx;
    private String prefix;
    private String qosSettingsPath;
    private ResourceStorage storage;
    private MBeanServer mbeanServer;

    private final String QOS_URI = "/test/server/admin/v1/qos";

    @Before
    public void init() {
        storage = new MockResourceStorage();
        vertx = Mockito.mock(Vertx.class);
        Mockito.when(vertx.eventBus()).thenReturn(Mockito.mock(EventBus.class));

        mbeanServer = Mockito.mock(MBeanServer.class);
        when(mbeanServer.isRegistered(any(ObjectName.class))).thenReturn(Boolean.TRUE);

        prefix = "test";
        qosSettingsPath = "/test/server/admin/v1/qos";
    }

    @Test
    public void testQoSSettingsUpdateWithValidConfigurations(TestContext context){
        QoSHandler qosHandler = new QoSHandler(vertx, storage, qosSettingsPath, new HashMap<>(), prefix);
        HttpServerResponse response = mock(HttpServerResponse.class);

        String validMinimalConfig = "{" +
                "  \"config\":{" +
                "    \"percentile\":75," +
                "    \"quorum\":40," +
                "    \"period\":5," +
                "    \"minSampleCount\" : 1000," +
                "    \"minSentinelCount\" : 5" +
                "  }" +
                "}";

        CustomHttpServerRequest request = new CustomHttpServerRequest(QOS_URI, HttpMethod.PUT, validMinimalConfig, new CaseInsensitiveHeaders(), response);
        qosHandler.handle(request);

        verify(response, times(1)).end();
        
        String validFullConfig = "{" +
                "  \"config\":{" +
                "    \"percentile\":75," +
                "    \"quorum\":40," +
                "    \"period\":5," +
                "    \"minSampleCount\" : 1000," +
                "    \"minSentinelCount\" : 5" +
                "  }," +
                "  \"sentinels\":{" +
                "    \"sentinelA\":{" +
                "      \"percentile\":50" +
                "    }," +
                "    \"sentinelB\":{}," +
                "    \"sentinelC\":{ \"minLowestPercentileValueMs\": 0.1}," +
                "    \"sentinelD\":{}" +
                "  }," +
                "  \"rules\":{" +
                "    \"/test/myapi1/v1/.*\":{" +
                "      \"reject\":1.2," +
                "      \"warn\":0.5" +
                "    }," +
                "    \"/test/myapi2/v1/.*\":{" +
                "      \"reject\":0.3" +
                "    }" +
                "  }" +
                "}";

        response = mock(HttpServerResponse.class);
        request = new CustomHttpServerRequest(QOS_URI, HttpMethod.PUT, validFullConfig, new CaseInsensitiveHeaders(), response);
        qosHandler.handle(request);

        verify(response, times(1)).end();
    }

    @Test
    public void testQoSSettingsUpdateWithInvalidPercentileValues(TestContext context){
        QoSHandler qosHandler = new QoSHandler(vertx, storage, qosSettingsPath, new HashMap<>(), prefix);
        HttpServerResponse response = spy(new CustomHttpServerResponse(new CaseInsensitiveHeaders()));

        String percentileNotAnyOfExpectedNumber = "{" +
                "  \"config\":{" +
                "    \"percentile\":11," +
                "    \"quorum\":40," +
                "    \"period\":5," +
                "    \"minSampleCount\" : 1000," +
                "    \"minSentinelCount\" : 5" +
                "  }" +
                "}";

        CustomHttpServerRequest request = new CustomHttpServerRequest(QOS_URI, HttpMethod.PUT, percentileNotAnyOfExpectedNumber, new CaseInsensitiveHeaders(), response);
        qosHandler.handle(request);

        verify(response, times(1)).setStatusCode(eq(StatusCode.BAD_REQUEST.getStatusCode()));
        verify(response, times(1)).setStatusMessage(eq("Bad Request Validation failed"));

        String percentileNotANumber = "{" +
                "  \"config\":{" +
                "    \"percentile\": \"boom\"," +
                "    \"quorum\":40," +
                "    \"period\":5," +
                "    \"minSampleCount\" : 1000," +
                "    \"minSentinelCount\" : 5" +
                "  }" +
                "}";

        response = spy(new CustomHttpServerResponse(new CaseInsensitiveHeaders()));
        request = new CustomHttpServerRequest(QOS_URI, HttpMethod.PUT, percentileNotANumber, new CaseInsensitiveHeaders(), response);
        qosHandler.handle(request);

        verify(response, times(1)).setStatusCode(eq(StatusCode.BAD_REQUEST.getStatusCode()));
        verify(response, times(1)).setStatusMessage(eq("Bad Request Validation failed"));
    }

    @Test
    public void testQoSSettingsUpdateWithRulesButNoSentinels(TestContext context){
        QoSHandler qosHandler = new QoSHandler(vertx, storage, qosSettingsPath, new HashMap<>(), prefix);
        HttpServerResponse response = spy(new CustomHttpServerResponse(new CaseInsensitiveHeaders()));

        String rulesWithoutSentinelsConfig = "{" +
                "  \"config\":{" +
                "    \"percentile\":75," +
                "    \"quorum\":40," +
                "    \"period\":5," +
                "    \"minSampleCount\" : 1000," +
                "    \"minSentinelCount\" : 5" +
                "  }," +
                "  \"rules\":{" +
                "    \"/test/myapi1/v1/.*\":{" +
                "      \"reject\":1.2," +
                "      \"warn\":0.5" +
                "    }," +
                "    \"/test/myapi2/v1/.*\":{" +
                "      \"reject\":0.3" +
                "    }" +
                "  }" +
                "}";

        CustomHttpServerRequest request = new CustomHttpServerRequest(QOS_URI, HttpMethod.PUT, rulesWithoutSentinelsConfig, new CaseInsensitiveHeaders(), response);
        qosHandler.handle(request);

        verify(response, times(1)).setStatusCode(eq(StatusCode.BAD_REQUEST.getStatusCode()));
        verify(response, times(1)).setStatusMessage(eq("Bad Request ValidationException: QoS settings contain rules without sentinels"));
    }

    private class CustomHttpServerRequest extends DummyHttpServerRequest {

        private String uri;
        private HttpMethod method;
        private String body;
        private MultiMap headers;
        private HttpServerResponse response;

        public CustomHttpServerRequest(String uri, HttpMethod method, String body, MultiMap headers, HttpServerResponse response) {
            this.uri = uri;
            this.method = method;
            this.body = body;
            this.headers = headers;
            this.response = response;
        }

        @Override public HttpMethod method() {
            return method;
        }
        @Override public String uri() {
            return uri;
        }
        @Override public MultiMap headers() { return headers; }

        @Override
        public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
            bodyHandler.handle(Buffer.buffer(body));
            return this;
        }

        @Override public HttpServerResponse response() { return response; }
    }

    class CustomHttpServerResponse extends DummyHttpServerResponse {

        private MultiMap headers;

        public CustomHttpServerResponse(MultiMap headers){
            this.headers = headers;
        }

        @Override public MultiMap headers() { return headers; }
    }

    @Test
    public void testSentinelMinLowestPercentileValue(TestContext context){

        QoSHandler qosHandler = new QoSHandler(vertx, storage, qosSettingsPath, new HashMap<>(), prefix);

        String configNoMinReponseTime = "{" +
                "  \"config\":{" +
                "    \"percentile\":75," +
                "    \"quorum\":40," +
                "    \"period\":5," +
                "    \"minSampleCount\" : 1000," +
                "    \"minSentinelCount\" : 5" +
                "  }," +
                "  \"sentinels\":{" +
                "    \"sentinelA\":{" +
                "      \"percentile\":50" +
                "    }," +
                "    \"sentinelB\":{}," +
                "    \"sentinelC\":{}," +
                "    \"sentinelD\":{}" +
                "  }," +
                "  \"rules\":{" +
                "    \"/test/myapi1/v1/.*\":{" +
                "      \"reject\":1.2," +
                "      \"warn\":0.5" +
                "    }," +
                "    \"/test/myapi2/v1/.*\":{" +
                "      \"reject\":0.3" +
                "    }" +
                "  }" +
                "}";

        JsonObject qosSettings = new JsonObject(configNoMinReponseTime);

        List<QoSSentinel> sentinels = qosHandler.createQoSSentinels(qosSettings);
        qosHandler.setQosSentinels(sentinels);

        // set values for lowest percentile value
        sentinels.get(0).setLowestPercentileValue(5.5); //sentinelA
        sentinels.get(1).setLowestPercentileValue(10.9); //sentinelB
        sentinels.get(2).setLowestPercentileValue(200.0); //sentinelC

        context.assertEquals(5.5, sentinels.get(0).getLowestPercentileValue());
        context.assertEquals(10.9, sentinels.get(1).getLowestPercentileValue());
        context.assertEquals(200.0, sentinels.get(2).getLowestPercentileValue());
        context.assertEquals(Double.MAX_VALUE, sentinels.get(3).getLowestPercentileValue());

        context.assertNull(sentinels.get(0).getLowestPercentileMinValue());
        context.assertNull(sentinels.get(1).getLowestPercentileMinValue());
        context.assertNull(sentinels.get(2).getLowestPercentileMinValue());
        context.assertNull(sentinels.get(3).getLowestPercentileMinValue());

        // create sentinels again, the existing lowest percentile values should not be overriden
        sentinels = qosHandler.createQoSSentinels(qosSettings);

        context.assertEquals(5.5, sentinels.get(0).getLowestPercentileValue());
        context.assertEquals(10.9, sentinels.get(1).getLowestPercentileValue());
        context.assertEquals(200.0, sentinels.get(2).getLowestPercentileValue());
        context.assertEquals(Double.MAX_VALUE, sentinels.get(3).getLowestPercentileValue());

        // update the settings with minLowestPercentileValueMs values

        String configWithMinResponseTime = "{" +
                "  \"config\":{" +
                "    \"percentile\":75," +
                "    \"quorum\":40," +
                "    \"period\":5," +
                "    \"minSampleCount\" : 1000," +
                "    \"minSentinelCount\" : 5" +
                "  }," +
                "  \"sentinels\":{" +
                "    \"sentinelA\":{" +
                "      \"percentile\":50" +
                "    }," +
                "    \"sentinelB\":{}," +
                "    \"sentinelC\":{ \"minLowestPercentileValueMs\": 250.5}," +
                "    \"sentinelD\":{ \"minLowestPercentileValueMs\": 50}" +
                "  }," +
                "  \"rules\":{" +
                "    \"/test/myapi1/v1/.*\":{" +
                "      \"reject\":1.2," +
                "      \"warn\":0.5" +
                "    }," +
                "    \"/test/myapi2/v1/.*\":{" +
                "      \"reject\":0.3" +
                "    }" +
                "  }" +
                "}";

        qosSettings = new JsonObject(configWithMinResponseTime);
        sentinels = qosHandler.createQoSSentinels(qosSettings);

        context.assertEquals(5.5, sentinels.get(0).getLowestPercentileValue(), "should still be the same (old) value");
        context.assertEquals(10.9, sentinels.get(1).getLowestPercentileValue(), "should still be the same (old) value");
        context.assertEquals(250.5, sentinels.get(2).getLowestPercentileValue(), "should have been overriden with the minLowestPercentileValueMs value");
        context.assertEquals(Double.MAX_VALUE, sentinels.get(3).getLowestPercentileValue(), "should still be the same (old) value, because lowest percentile value is greater than minLowestPercentileValueMs");

        context.assertNull(sentinels.get(0).getLowestPercentileMinValue());
        context.assertNull(sentinels.get(1).getLowestPercentileMinValue());
        context.assertEquals(250.5, sentinels.get(2).getLowestPercentileMinValue());
        context.assertEquals(50.0, sentinels.get(3).getLowestPercentileMinValue());
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

        when(mbeanServer.getAttribute(sentinel1, "Count")).thenReturn(1L);
        when(mbeanServer.getAttribute(sentinel2, "Count")).thenReturn(1L);
        when(mbeanServer.getAttribute(sentinel3, "Count")).thenReturn(1L);
        when(mbeanServer.getAttribute(sentinel4, "Count")).thenReturn(1L);
        when(mbeanServer.getAttribute(sentinel5, "Count")).thenReturn(1L);

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

        when(mbeanServer.getAttribute(sentinel1, "Count")).thenReturn(1L);
        when(mbeanServer.getAttribute(sentinel2, "Count")).thenReturn(1L);
        when(mbeanServer.getAttribute(sentinel3, "Count")).thenReturn(1L);
        when(mbeanServer.getAttribute(sentinel4, "Count")).thenReturn(1L);
        when(mbeanServer.getAttribute(sentinel5, "Count")).thenReturn(1L);

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
     * Test that the lowestPercentileValue will never be set to 0.0
     *
     * @throws Exception
     */
    @Test
    public void testSetLowestPercentileValue(TestContext context) throws Exception {
        QoSHandler qosHandler = new QoSHandler(vertx, storage, qosSettingsPath, new HashMap<>(), prefix);
        qosHandler.setMBeanServer(mbeanServer);

        ObjectName sentinel1 = new ObjectName(getObjectName("sentinel1"));

        when(mbeanServer.getAttribute(sentinel1, "75thPercentile")).thenReturn(
                0.0,    //1st
                0.5, //2nd
                0.3,          //3rd
                0.6,          //4th
                0.0,          //5th
                0.2,          //6th
                0.05,         //7th
                0.09,         //8th
                0.00001,      //9th
                0.0           //10th
        );

        when(mbeanServer.getAttribute(sentinel1, "Count")).thenReturn(100L);

        // => average response time = 1.3, 40% index = 2 (value = 1.5)
        QoSConfig config = new QoSConfig(75, 40, 5, 0, 0);
        qosHandler.setGlobalQoSConfig(config);

        List<QoSSentinel> sentinels = new ArrayList<>();
        sentinels.add(new QoSSentinel("sentinel1"));
        qosHandler.setQosSentinels(sentinels);

        List<QoSRule> rules = new ArrayList<>();
        QoSRule rule1 = new QoSRule(Pattern.compile("/test1/*"));
        rule1.setReject(0.9);
        rules.add(rule1);
        qosHandler.setQosRules(rules);

        QoSSentinel sentinel = sentinels.get(0);

        qosHandler.evaluateQoSActions();
        context.assertEquals(Double.MAX_VALUE, sentinel.getLowestPercentileValue(), "the 1st response time is 0.0 and therefore should be ignored");

        qosHandler.evaluateQoSActions();
        context.assertEquals(0.5, sentinel.getLowestPercentileValue(), "the 2nd response time is lower than Double.MAX_VALUE and should be used");

        qosHandler.evaluateQoSActions();
        context.assertEquals(0.3, sentinel.getLowestPercentileValue(), "the 3rd response time is lower than 0.5 and should be used");

        qosHandler.evaluateQoSActions();
        context.assertEquals(0.3, sentinel.getLowestPercentileValue(), "the 4th response time is higher than 0.3 and should be ignored");

        qosHandler.evaluateQoSActions();
        context.assertEquals(0.3, sentinel.getLowestPercentileValue(), "the 5th response time is 0.0 and therefore should be ignored");

        qosHandler.evaluateQoSActions();
        context.assertEquals(0.2, sentinel.getLowestPercentileValue(), "the 6th response time is lower than 0.3 and should be used");

        qosHandler.evaluateQoSActions();
        context.assertEquals(0.05, sentinel.getLowestPercentileValue(), "the 7th response time is lower than 0.2 and should be used");

        qosHandler.evaluateQoSActions();
        context.assertEquals(0.05, sentinel.getLowestPercentileValue(), "the 8th response time is higher than 0.05 and should be ignored");

        qosHandler.evaluateQoSActions();
        context.assertEquals(0.00001, sentinel.getLowestPercentileValue(), "the 9th response time is lower than 0.05 and should be used");

        qosHandler.evaluateQoSActions();
        context.assertEquals(0.00001, sentinel.getLowestPercentileValue(), "the 10th response time is 0.0 and therefore should be ignored");
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

package org.swisspush.gateleen.monitoring;

import com.jayway.awaitility.Duration;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.redis.RedisClient;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.util.Address;

import java.util.Map;
import java.util.concurrent.Callable;
import static com.jayway.awaitility.Awaitility.await;


/**
 * Tests for the {@link MonitoringHandler} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class MonitoringHandlerTest {

    private Vertx vertx;
    private RedisClient redisClient;
    private MockResourceStorage storage;

    private final String PREFIX = "gateleen.";
    private final String PROPERTY_NAME = "some_property_name";
    private final String REQUEST_PER_RULE_MONITORING_PATH = "/playground/server/monitoring/rpr";

    @Before
    public void setUp(){
        vertx = Vertx.vertx();
        redisClient = Mockito.mock(RedisClient.class);
        storage = new MockResourceStorage();
    }

    @Test
    public void testActiveRequestPerRuleMonitoring(TestContext testContext){
        activateRequestPerRuleMonitoring(false);
        MonitoringHandler mh = new MonitoringHandler(vertx, redisClient, storage, PREFIX);
        testContext.assertFalse(mh.isRequestPerRuleMonitoringActive(),
                "request per rule monitoring should not be active since no system property was set");

        // set system property and check again
        activateRequestPerRuleMonitoring(true);
        MonitoringHandler mh2 = new MonitoringHandler(vertx, redisClient, storage, PREFIX);
        testContext.assertTrue(mh2.isRequestPerRuleMonitoringActive(),
                "request per rule monitoring should be active since the correct system property was set");
    }

    @Test
    public void testInitRequestPerRuleMonitoringPath(TestContext testContext){
        MonitoringHandler mh = new MonitoringHandler(vertx, redisClient, storage, PREFIX);
        testContext.assertNull(mh.getRequestPerRuleMonitoringPath());

        mh = new MonitoringHandler(vertx, redisClient, storage, PREFIX, "/gateleen/monitoring/rpr/");
        testContext.assertNotNull(mh.getRequestPerRuleMonitoringPath());
        testContext.assertFalse(mh.getRequestPerRuleMonitoringPath().endsWith("/"));

        mh = new MonitoringHandler(vertx, redisClient, storage, PREFIX, "/gateleen/monitoring/rpr");
        testContext.assertNotNull(mh.getRequestPerRuleMonitoringPath());
        testContext.assertFalse(mh.getRequestPerRuleMonitoringPath().endsWith("/"));
    }

    @Test
    public void testInitSamplingAndExpiry(TestContext testContext){
        activateRequestPerRuleMonitoring(true);
        MonitoringHandler mh = new MonitoringHandler(vertx, redisClient, storage, PREFIX);
        testContext.assertEquals(MonitoringHandler.REQUEST_PER_RULE_DEFAULT_SAMPLING, mh.getRequestPerRuleSampling());
        testContext.assertEquals(MonitoringHandler.REQUEST_PER_RULE_DEFAULT_EXPIRY, mh.getRequestPerRuleExpiry());

        // change sampling and expiry through system property
        System.setProperty(MonitoringHandler.REQUEST_PER_RULE_SAMPLING_PROPERTY, "10000");
        System.setProperty(MonitoringHandler.REQUEST_PER_RULE_EXPIRY_PROPERTY, "120");
        mh = new MonitoringHandler(vertx, redisClient, storage, PREFIX);

        testContext.assertEquals(10000L, mh.getRequestPerRuleSampling());
        testContext.assertEquals(120L, mh.getRequestPerRuleExpiry());
    }

    @Test
    public void testConsumeMonitoringAddress(TestContext testContext){
        Async async = testContext.async();

        activateRequestPerRuleMonitoring(true);
        System.setProperty(MonitoringHandler.REQUEST_PER_RULE_SAMPLING_PROPERTY, "100");
        MonitoringHandler mh = new MonitoringHandler(vertx, redisClient, storage, PREFIX);

        PUTRequest request = new PUTRequest();
        request.addHeader(PROPERTY_NAME, "my_value_123");
        mh.updateRequestPerRuleMonitoring(request, "a_fancy_rule");

        vertx.eventBus().consumer(Address.monitoringAddress(), new Handler<Message<JsonObject>>() {
            public void handle(Message<JsonObject> message) {
                final JsonObject body = message.body();
                testContext.assertEquals("gateleen.rpr.my_value_123=a_fancy_rule", body.getString("name"));
                async.complete();
            }
        });
    }

    @Test
    public void testWriteRequestPerRuleMonitoringToStorage(){

        activateRequestPerRuleMonitoring(true);
        System.setProperty(MonitoringHandler.REQUEST_PER_RULE_SAMPLING_PROPERTY, "100");
        MonitoringHandler mh = new MonitoringHandler(vertx, redisClient, storage, PREFIX, REQUEST_PER_RULE_MONITORING_PATH);

        PUTRequest request = new PUTRequest();
        request.addHeader(PROPERTY_NAME, "my_value_123");
        mh.updateRequestPerRuleMonitoring(request, "a_fancy_rule");

        await().atMost(Duration.ONE_SECOND).until(storageContainsData("my_value_123=a_fancy_rule"));
    }

    private Callable<Boolean> storageContainsData(String valueToLookFor) {
        return () -> {
            boolean dataFound = false;
            for (Map.Entry<String, String> entry : storage.getMockData().entrySet()) {
                if(entry.getKey().contains(valueToLookFor)){
                    dataFound = true;
                    break;
                }
            }
            return dataFound;
        };
    }

    class PUTRequest extends DummyHttpServerRequest {
        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();

        @Override public HttpMethod method() {
            return HttpMethod.PUT;
        }
        @Override public String uri() {
            return "/playground/server/some_resource";
        }
        @Override public MultiMap headers() { return headers; }

        @Override
        public String getHeader(String headerName) { return headers.get(headerName); }

        public void addHeader(String headerName, String headerValue){ headers.add(headerName, headerValue); }
    }

    private void activateRequestPerRuleMonitoring(boolean activate){
        if(activate){
            System.setProperty(MonitoringHandler.REQUEST_PER_RULE_PROPERTY, PROPERTY_NAME);
        } else {
            System.clearProperty(MonitoringHandler.REQUEST_PER_RULE_PROPERTY);
        }
    }
}

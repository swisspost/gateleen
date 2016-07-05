package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import com.google.common.collect.ImmutableMap;
import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.routing.Rule;
import org.swisspush.gateleen.routing.RuleProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests for the {@link QueueCircuitBreakerRulePatternToEndpointMapping} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class QueueCircuitBreakerRulePatternToEndpointMappingTest {

    @org.junit.Rule
    public Timeout rule = Timeout.seconds(5);

    private Vertx vertx;
    private RuleProvider ruleProvider;
    private List<Rule> rules;
    private QueueCircuitBreakerRulePatternToEndpointMapping mapping;

    private final String RULES_STORAGE_INITIAL = "{\n" +
            " \"/playground/css/(.*)\": {\n" +
            "  \"description\": \"Pages CSS\",\n" +
            "  \"path\": \"/playground/server/pages/css/$1\",\n" +
            "  \"storage\": \"main\"\n" +
            " },\n" +
            " \"/playground/js/(.*)\": {\n" +
            "  \"description\": \"Pages JS\",\n" +
            "  \"path\": \"/playground/server/pages/js/$1\",\n" +
            "  \"storage\": \"main\"\n" +
            " },\n" +
            " \"/playground/img/(.*)\": {\n" +
            "  \"description\": \"Pages Images\",\n" +
            "  \"path\": \"/playground/server/pages/img/$1\",\n" +
            "  \"storage\": \"main\"\n" +
            " }\n" +
            "}";

    @Before
    public void setUp(TestContext context){
        Async async = context.async();
        vertx = Vertx.vertx();
        String rulesPath = "/gateleen/server/admin/v1/routing/rules";
        ResourceStorage storage = new MockResourceStorage(ImmutableMap.of(rulesPath, RULES_STORAGE_INITIAL));
        Map<String, Object> properties = new HashMap<>();
        ruleProvider = new RuleProvider(vertx, rulesPath, storage, properties);
        mapping = new QueueCircuitBreakerRulePatternToEndpointMapping();
        ruleProvider.getRules().setHandler(event -> {
            rules = event.result();
            async.complete();
        });
    }

    @Test
    public void testGetEndpointFromRequestUri(TestContext context){
        context.assertNotNull(rules);
        context.assertEquals(3, rules.size());

        mapping.updateRulePatternToEndpointMapping(rules);

        String endpoint_1 = mapping.getEndpointFromRequestUri("/playground/img/test.jpg").getEndpointHash();
        context.assertNotNull(endpoint_1);
        String endpoint_2 = mapping.getEndpointFromRequestUri("/playground/js/code.js").getEndpointHash();
        context.assertNotNull(endpoint_2);
        context.assertNotEquals(endpoint_1, endpoint_2);

        String endpoint_2a = mapping.getEndpointFromRequestUri("/playground/js/another.js").getEndpointHash();
        context.assertNotNull(endpoint_2a);
        context.assertEquals(endpoint_2, endpoint_2a);

        PatternAndEndpointHash enpoint_3 = mapping.getEndpointFromRequestUri("/playground/unknown/uri");
        context.assertNull(enpoint_3);
    }

    @Test
    public void testEndpointsRemainAfterRulesUpdate(TestContext context){
        context.assertNotNull(rules);
        context.assertEquals(3, rules.size());

        mapping.updateRulePatternToEndpointMapping(rules);

        String endpoint_1_before = mapping.getEndpointFromRequestUri("/playground/img/test.jpg").getEndpointHash();
        context.assertNotNull(endpoint_1_before);
        String endpoint_2_before = mapping.getEndpointFromRequestUri("/playground/js/code.js").getEndpointHash();
        context.assertNotNull(endpoint_2_before);
        context.assertNotEquals(endpoint_1_before, endpoint_2_before);

        mapping.updateRulePatternToEndpointMapping(rules);

        String endpoint_1_after = mapping.getEndpointFromRequestUri("/playground/img/test.jpg").getEndpointHash();
        context.assertNotNull(endpoint_1_after);
        String endpoint_2_after = mapping.getEndpointFromRequestUri("/playground/js/code.js").getEndpointHash();
        context.assertNotNull(endpoint_2_after);
        context.assertNotEquals(endpoint_1_after, endpoint_2_after);
    }
}

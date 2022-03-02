package org.swisspush.gateleen.queue.queuing.circuitbreaker.util;

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
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.PatternAndCircuitHash;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitBreakerRulePatternToCircuitMapping;
import org.swisspush.gateleen.routing.Rule;
import org.swisspush.gateleen.routing.RuleProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests for the {@link QueueCircuitBreakerRulePatternToCircuitMapping} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class QueueCircuitBreakerRulePatternToCircuitMappingTest {

    @org.junit.Rule
    public Timeout rule = Timeout.seconds(5);

    private Vertx vertx;
    private RuleProvider ruleProvider;
    private List<Rule> rules;
    private List<Rule> rulesAfterUpdate;
    private QueueCircuitBreakerRulePatternToCircuitMapping mapping;

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

    private final String RULES_STORAGE_AFTER_UPDATE = "{\n" +
            " \"/playground/css/(.*)\": {\n" +
            "  \"description\": \"Pages CSS\",\n" +
            "  \"path\": \"/playground/server/pages/css/$1\",\n" +
            "  \"storage\": \"main\"\n" +
            " },\n" +
            " \"/playground/pages/(.*)\": {\n" +
            "  \"description\": \"Pages JS\",\n" +
            "  \"path\": \"/playground/server/pages/js/$1\",\n" +
            "  \"storage\": \"main\"\n" +
            " },\n" +
            " \"/playground/videos/(.*)\": {\n" +
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
        mapping = new QueueCircuitBreakerRulePatternToCircuitMapping();
        ruleProvider.getRules().onComplete(event -> {
            rules = event.result();
            ((MockResourceStorage)storage).putMockData(rulesPath, RULES_STORAGE_AFTER_UPDATE);
            ruleProvider.getRules().onComplete(event1 -> {
                rulesAfterUpdate = event1.result();
                async.complete();
            });
        });
    }

    @Test
    public void testGetCircuitFromRequestUri(TestContext context){
        context.assertNotNull(rules);
        context.assertNotNull(rulesAfterUpdate);
        context.assertEquals(3, rules.size());
        context.assertEquals(4, rulesAfterUpdate.size());

        List<PatternAndCircuitHash> patternAndCircuitHashes = mapping.updateRulePatternToCircuitMapping(rules);
        context.assertEquals(0, patternAndCircuitHashes.size());

        String circuit_1 = mapping.getCircuitFromRequestUri("/playground/img/test.jpg").getCircuitHash();
        context.assertNotNull(circuit_1);
        String circuit_2 = mapping.getCircuitFromRequestUri("/playground/js/code.js").getCircuitHash();
        context.assertNotNull(circuit_2);
        context.assertNotEquals(circuit_1, circuit_2);

        String circuit_2a = mapping.getCircuitFromRequestUri("/playground/js/another.js").getCircuitHash();
        context.assertNotNull(circuit_2a);
        context.assertEquals(circuit_2, circuit_2a);

        PatternAndCircuitHash circuit_3 = mapping.getCircuitFromRequestUri("/playground/unknown/uri");
        context.assertNull(circuit_3);
    }

    @Test
    public void testCircuitsRemainAfterRulesUpdate(TestContext context){
        context.assertNotNull(rules);
        context.assertNotNull(rulesAfterUpdate);
        context.assertEquals(3, rules.size());
        context.assertEquals(4, rulesAfterUpdate.size());

        List<PatternAndCircuitHash> patternAndCircuitHashes = mapping.updateRulePatternToCircuitMapping(rules);
        context.assertEquals(0, patternAndCircuitHashes.size());

        String circuit_1_before = mapping.getCircuitFromRequestUri("/playground/img/test.jpg").getCircuitHash();
        context.assertNotNull(circuit_1_before);
        String circuit_2_before = mapping.getCircuitFromRequestUri("/playground/js/code.js").getCircuitHash();
        context.assertNotNull(circuit_2_before);
        context.assertNotEquals(circuit_1_before, circuit_2_before);

        List<PatternAndCircuitHash> patternAndCircuitHashesAfterUpdate = mapping.updateRulePatternToCircuitMapping(rules);
        context.assertEquals(0, patternAndCircuitHashesAfterUpdate.size());

        String circuit_1_after = mapping.getCircuitFromRequestUri("/playground/img/test.jpg").getCircuitHash();
        context.assertNotNull(circuit_1_after);
        String circuit_2_after = mapping.getCircuitFromRequestUri("/playground/js/code.js").getCircuitHash();
        context.assertNotNull(circuit_2_after);
        context.assertNotEquals(circuit_1_after, circuit_2_after);
    }

    @Test
    public void testGetRemovedPatternAndCircuitHashAfterUpdate(TestContext context){
        context.assertNotNull(rules);
        context.assertNotNull(rulesAfterUpdate);
        context.assertEquals(3, rules.size());
        context.assertEquals(4, rulesAfterUpdate.size());

        List<PatternAndCircuitHash> patternAndCircuitHashes = mapping.updateRulePatternToCircuitMapping(rules);
        context.assertEquals(0, patternAndCircuitHashes.size());

        List<PatternAndCircuitHash> patternAndCircuitHashesAfterUpdate = mapping.updateRulePatternToCircuitMapping(rulesAfterUpdate);
        context.assertEquals(1, patternAndCircuitHashesAfterUpdate.size());
        PatternAndCircuitHash removedPatternAndCircuitHash = patternAndCircuitHashesAfterUpdate.get(0);
        context.assertEquals("/playground/js/(.*)", removedPatternAndCircuitHash.getPattern().pattern());
    }
}

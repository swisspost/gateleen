package org.swisspush.gateleen.routing;

import com.google.common.collect.ImmutableMap;
import io.vertx.core.Future;
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
import org.swisspush.gateleen.core.util.Address;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.swisspush.gateleen.routing.RuleProvider.RuleChangesObserver;

/**
 * Tests for the {@link RuleProvider} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class RuleProviderTest {

    @org.junit.Rule
    public Timeout rule = Timeout.seconds(5);

    private Vertx vertx;
    private String rulesPath;
    private ResourceStorage storage;
    private Map<String, Object> properties;

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

    private final String RULES_STORAGE_UPDATED = "{\n" +
            " \"/playground/css/(.*)\": {\n" +
            "  \"description\": \"Pages CSS\",\n" +
            "  \"path\": \"/playground/server/pages/css/$1\",\n" +
            "  \"storage\": \"main\"\n" +
            " },\n" +
            " \"/playground/img/(.*)\": {\n" +
            "  \"description\": \"Pages Images\",\n" +
            "  \"path\": \"/playground/server/pages/img/$1\",\n" +
            "  \"storage\": \"main\"\n" +
            " }\n" +
            "}";

    private final String RULES_WITH_MISSING_PROPS = "{\n"
            + "  \"/gateleen/rule/1\": {\n"
            + "    \"description\": \"Test Rule 1\",\n"
            + "    \"url\": \"${gateleen.test.prop.1}/gateleen/rule/1\"\n"
            + "  },\n"
            + "  \"/gateleen/rule/2\": {\n"
            + "    \"description\": \"Test Rule 2\",\n"
            + "    \"url\": \"${gateleen.test.prop.2}/gateleen/rule/2\"\n"
            + "  }\n"
            + "}";

    @Before
    public void setUp(){
        vertx = Vertx.vertx();
        rulesPath = "/gateleen/server/admin/v1/routing/rules";
        storage = new MockResourceStorage(ImmutableMap.of(rulesPath, RULES_STORAGE_INITIAL));
        properties = new HashMap<>();
    }

    @Test
    public void testGetRulesWithWrongRulesPath(TestContext context){
        RuleProvider ruleProvider = new RuleProvider(vertx, "/some/wrong/path", storage, properties);
        Future<List<Rule>> rulesFuture = ruleProvider.getRules();
        context.assertTrue(rulesFuture.failed(), "getRules() future should not have been successful");
        context.assertNotNull(rulesFuture.cause());
        context.assertTrue(rulesFuture.cause().getMessage().contains("Could not get URL"));
    }

    @Test
    public void testGetRulesWithMissingPropertiesinRoutingRules(TestContext context){
        ((MockResourceStorage)storage).putMockData(rulesPath, RULES_WITH_MISSING_PROPS);
        RuleProvider ruleProvider = new RuleProvider(vertx, rulesPath, storage, properties);
        Future<List<Rule>> rulesFuture = ruleProvider.getRules();
        context.assertTrue(rulesFuture.failed(), "getRules() future should not have been successful");
        System.out.println(rulesFuture.cause().getMessage());
        context.assertNotNull(rulesFuture.cause());
        context.assertTrue(rulesFuture.cause().getMessage().contains("Could not resolve gateleen.test.prop.1"));
    }

    @Test
    public void testGetRulesAfterInitialization(TestContext context){
        RuleProvider ruleProvider = new RuleProvider(vertx, rulesPath, storage, properties);
        Future<List<Rule>> rulesFuture = ruleProvider.getRules();
        context.assertTrue(rulesFuture.succeeded(), "getRules() future should have been successful");
        context.assertNotNull(rulesFuture.result(), "The list of rules should not be null");
        context.assertEquals(3, rulesFuture.result().size(), "There should be exactly 3 rules");

    }

    @Test
    public void testUpdateRulesOnEventBusEvent(TestContext context) throws InterruptedException {
        RuleProvider ruleProvider = new RuleProvider(vertx, rulesPath, storage, properties);
        Future<List<Rule>> rulesFuture = ruleProvider.getRules();
        context.assertTrue(rulesFuture.succeeded(), "getRules() future should have been successful");
        context.assertNotNull(rulesFuture.result(), "The list of rules should not be null");
        context.assertEquals(3, rulesFuture.result().size(), "There should be exactly 3 rules");

        // change routing rules and send event bus message
        ((MockResourceStorage)storage).putMockData(rulesPath, RULES_STORAGE_UPDATED);
        vertx.eventBus().publish(Address.RULE_UPDATE_ADDRESS, true);

        Future<List<Rule>> rulesFuture2 = ruleProvider.getRules();

        context.assertTrue(rulesFuture2.succeeded(), "getRules() future should have been successful");
        context.assertNotNull(rulesFuture2.result(), "The list of rules should not be null");
        context.assertEquals(2, rulesFuture2.result().size(), "There should be exactly 2 rules after update");
    }

    @Test
    public void testNotificationOfRuleChangeObservers(TestContext context){
        Async async = context.async();

        RuleProvider ruleProvider = new RuleProvider(vertx, rulesPath, storage, properties);
        DummyRuleChangesObserver observer = new DummyRuleChangesObserver(async, context);
        ruleProvider.registerObserver(observer);
        observer.setExpectedRulesListSize(2); // after the update there will be 2 routing rules

        Future<List<Rule>> rulesFuture = ruleProvider.getRules();
        context.assertTrue(rulesFuture.succeeded(), "getRules() future should have been successful");
        context.assertNotNull(rulesFuture.result(), "The list of rules should not be null");
        context.assertEquals(3, rulesFuture.result().size(), "There should be exactly 3 rules");

        // change routing rules and send event bus message
        ((MockResourceStorage)storage).putMockData(rulesPath, RULES_STORAGE_UPDATED);
        vertx.eventBus().publish(Address.RULE_UPDATE_ADDRESS, true);
    }

    static class DummyRuleChangesObserver implements RuleChangesObserver {

        private final Async async;
        private final TestContext testContext;
        private int expectedRulesListSize;

        public DummyRuleChangesObserver(Async async, TestContext testContext) {
            this.async = async;
            this.testContext = testContext;
        }

        public void setExpectedRulesListSize(int size){
            this.expectedRulesListSize = size;
        }

        @Override
        public void rulesChanged(List<Rule> rules) {
            testContext.assertNotNull(rules, "list of rules should not be null");
            testContext.assertEquals(expectedRulesListSize, rules.size());
            async.complete();
        }
    }
}

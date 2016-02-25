package org.swisspush.gateleen.routing.routing;

import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.swisspush.gateleen.routing.routing.RuleFeatures.Feature.EXPAND_ON_BACKEND;
import static org.swisspush.gateleen.routing.routing.RuleFeatures.Feature.STORAGE_EXPAND;


/**
 * Tests for the {@link RuleFeaturesProvider} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class RuleFeaturesProviderTest {

    @Test
    public void testStorageExpandFeature(TestContext context){
        List<Rule> rules = setUpRules();
        RuleFeaturesProvider provider = new RuleFeaturesProvider(rules);
        List<RuleFeatures> ruleFeatures = provider.getFeaturesList();
        assertCommonResults(context, ruleFeatures, rules);

        context.assertTrue(ruleFeatures.get(0).hasFeature(STORAGE_EXPAND));
        context.assertFalse(ruleFeatures.get(1).hasFeature(STORAGE_EXPAND));
        context.assertTrue(ruleFeatures.get(2).hasFeature(STORAGE_EXPAND));
        context.assertFalse(ruleFeatures.get(3).hasFeature(STORAGE_EXPAND));
        context.assertTrue(ruleFeatures.get(4).hasFeature(STORAGE_EXPAND));
        context.assertFalse(ruleFeatures.get(5).hasFeature(STORAGE_EXPAND));
        context.assertTrue(ruleFeatures.get(6).hasFeature(STORAGE_EXPAND));
        context.assertFalse(ruleFeatures.get(7).hasFeature(STORAGE_EXPAND));
        context.assertTrue(ruleFeatures.get(8).hasFeature(STORAGE_EXPAND));
        context.assertFalse(ruleFeatures.get(9).hasFeature(STORAGE_EXPAND));
    }

    @Test
    public void testExpandOnBackendFeature(TestContext context){
        List<Rule> rules = setUpRules();
        RuleFeaturesProvider provider = new RuleFeaturesProvider(rules);
        List<RuleFeatures> ruleFeatures = provider.getFeaturesList();
        assertCommonResults(context, ruleFeatures, rules);

        context.assertFalse(ruleFeatures.get(0).hasFeature(EXPAND_ON_BACKEND));
        context.assertFalse(ruleFeatures.get(1).hasFeature(EXPAND_ON_BACKEND));
        context.assertFalse(ruleFeatures.get(2).hasFeature(EXPAND_ON_BACKEND));
        context.assertFalse(ruleFeatures.get(3).hasFeature(EXPAND_ON_BACKEND));
        context.assertFalse(ruleFeatures.get(4).hasFeature(EXPAND_ON_BACKEND));
        context.assertTrue(ruleFeatures.get(5).hasFeature(EXPAND_ON_BACKEND));
        context.assertTrue(ruleFeatures.get(6).hasFeature(EXPAND_ON_BACKEND));
        context.assertTrue(ruleFeatures.get(7).hasFeature(EXPAND_ON_BACKEND));
        context.assertTrue(ruleFeatures.get(8).hasFeature(EXPAND_ON_BACKEND));
        context.assertTrue(ruleFeatures.get(9).hasFeature(EXPAND_ON_BACKEND));
    }

    @Test
    public void testIgnoreRulesWithInvalidPatterns(TestContext context){

        Rule ruleValid1 = createRule("/test/rules/rule_valid_1", true, false);
        Rule ruleValid2 = createRule("/test/rules/rule_valid_2", false, true);
        Rule ruleInvalid = createRule("/test/rules/(rule_invalid", true, false);

        RuleFeaturesProvider provider = new RuleFeaturesProvider(Arrays.asList(ruleInvalid, ruleValid1, ruleValid2));
        List<RuleFeatures> ruleFeatures = provider.getFeaturesList();

        context.assertEquals(2, ruleFeatures.size());
        context.assertTrue(ruleFeatures.get(0).hasFeature(STORAGE_EXPAND));
        context.assertFalse(ruleFeatures.get(0).hasFeature(EXPAND_ON_BACKEND));
        context.assertFalse(ruleFeatures.get(1).hasFeature(STORAGE_EXPAND));
        context.assertTrue(ruleFeatures.get(1).hasFeature(EXPAND_ON_BACKEND));
    }

    @Test
    public void testStorageExpandFeatureRequests(TestContext context){
        Rule rule1 = createRule("/root/element1/element2/(test1/.*/test2/.*/test3/.*)", true, false);
        Rule rule2 = createRule("/root/element11/element22/(.*)", true, false);
        Rule rule3 = createRule("/root/element111/element222/", true, false);

        RuleFeaturesProvider provider = new RuleFeaturesProvider(Arrays.asList(rule1, rule2, rule3));

        context.assertTrue(provider.isFeatureRequest(STORAGE_EXPAND, "/root/element1/element2/test1/1234/test2/23423/test3/asdf/asdf"));
        context.assertFalse(provider.isFeatureRequest(STORAGE_EXPAND, "/root/element1/element2/test999/1234/test2/23423/test3/asdf/asdf"));

        context.assertTrue(provider.isFeatureRequest(STORAGE_EXPAND, "/root/element11/element22/test1/1234/"));
        context.assertFalse(provider.isFeatureRequest(STORAGE_EXPAND, "/root/element11/element22"));

        context.assertTrue(provider.isFeatureRequest(STORAGE_EXPAND, "/root/element111/element222/"));
        context.assertFalse(provider.isFeatureRequest(STORAGE_EXPAND, "/root/element111/element222/asdf"));
    }

    @Test
    public void testStorageExpandRequestsFromSpecificToLessSpecific(TestContext context){
        Rule rule1 = createRule("/root/elements/123/(.*)", false, false);
        Rule rule2 = createRule("/root/elements/([0-9].*)", true, false);

        RuleFeaturesProvider provider = new RuleFeaturesProvider(Arrays.asList(rule1, rule2));

        context.assertFalse(provider.isFeatureRequest(STORAGE_EXPAND, "/root/elements/123/some/more/elements"));
        context.assertTrue(provider.isFeatureRequest(STORAGE_EXPAND, "/root/elements/999/some/more/elements"));
    }

    @Test
    public void testExpandOnBackendFeatureRequests(TestContext context){
        Rule rule1 = createRule("/root/element1/element2/(test1/.*/test2/.*/test3/.*)", false, true);
        Rule rule2 = createRule("/root/element11/element22/(.*)", false, true);
        Rule rule3 = createRule("/root/element111/element222/", false, true);

        RuleFeaturesProvider provider = new RuleFeaturesProvider(Arrays.asList(rule1, rule2, rule3));

        context.assertTrue(provider.isFeatureRequest(EXPAND_ON_BACKEND, "/root/element1/element2/test1/1234/test2/23423/test3/asdf/asdf"));
        context.assertFalse(provider.isFeatureRequest(EXPAND_ON_BACKEND, "/root/element1/element2/test999/1234/test2/23423/test3/asdf/asdf"));

        context.assertTrue(provider.isFeatureRequest(EXPAND_ON_BACKEND, "/root/element11/element22/test1/1234/"));
        context.assertFalse(provider.isFeatureRequest(EXPAND_ON_BACKEND, "/root/element11/element22"));

        context.assertTrue(provider.isFeatureRequest(EXPAND_ON_BACKEND, "/root/element111/element222/"));
        context.assertFalse(provider.isFeatureRequest(EXPAND_ON_BACKEND, "/root/element111/element222/asdf"));
    }

    private List<Rule> setUpRules(){
        List<Rule> rules = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Rule rule = new Rule();
            rule.setUrlPattern("/test/rules/rule" + i);

            if(i%2 == 0) {
                rule.setStorageExpand(true);
            } else {
                rule.setStorageExpand(false);
            }

            if(i > 4){
                rule.setExpandOnBackend(true);
            } else {
                rule.setExpandOnBackend(false);
            }
            rules.add(rule);
        }
        return rules;
    }

    private Rule createRule(String urlPattern, boolean storageExpand, boolean backendExpand){
        Rule rule = new Rule();
        rule.setUrlPattern(urlPattern);
        rule.setStorageExpand(storageExpand);
        rule.setExpandOnBackend(backendExpand);
        return rule;
    }

    private void assertCommonResults(TestContext context, List<RuleFeatures> ruleFeatures, List<Rule> rules){
        context.assertNotNull(ruleFeatures);
        context.assertEquals(10, rules.size());
        context.assertEquals(10, ruleFeatures.size());
    }
}

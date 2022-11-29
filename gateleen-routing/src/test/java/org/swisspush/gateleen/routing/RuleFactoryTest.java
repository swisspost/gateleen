package org.swisspush.gateleen.routing;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.ProxyType;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tests for the RuleFactory class
 *
 * @author https://github.com/hofer [Marc Hofer]
 */
@RunWith(VertxUnitRunner.class)
public class RuleFactoryTest {

    private Map<String, Object> properties;
    private String routingRulesSchema;

    @org.junit.Rule
    public ExpectedException thrown= ExpectedException.none();

    @Before
    public void setUp(){
        properties = new HashMap<>();
        properties.put("gateleen.test.prop.valid", "http://someserver/");
        routingRulesSchema = ResourcesUtils.loadResource("gateleen_routing_schema_routing_rules", true);
    }

    @Test
    public void testSimpleRuleConfigParsing(TestContext context) throws ValidationException {
        String simpleExampleRule = "{"
                + "  \"/gateleen/rule/1\": {"
                + "    \"description\": \"Test Rule 1\","
                + "    \"url\": \"${gateleen.test.prop.1}/gateleen/rule/1\""
                + "  },"
                + "  \"/gateleen/rule/2\": {"
                + "    \"description\": \"Test Rule 2\","
                + "    \"url\": \"${gateleen.test.prop.2}/gateleen/rule/2\""
                + "  }"
                + "}";

        properties.put("gateleen.test.prop.1", "http://someserver1/");
        properties.put("gateleen.test.prop.2", "http://someserver2/");

        List<Rule> rules =  new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(simpleExampleRule), Router.DEFAULT_ROUTER_MULTIPLIER);

        context.assertTrue(rules.size() == 2);
        context.assertEquals("someserver1", rules.get(0).getHost());
        context.assertEquals(60, rules.get(0).getKeepAliveTimeout());
        context.assertEquals("someserver2", rules.get(1).getHost());
        context.assertEquals(60, rules.get(1).getKeepAliveTimeout());
    }

    @Test
    public void testRuleWiteKeepAliveTimeoutParsing(TestContext context) throws ValidationException {
        String rule = "{"
                + "  \"/gateleen/rule/keep-alive\": {"
                + "    \"description\": \"Keep Alive Rule 1\","
                + "    \"url\": \"https://sawasdee.hello.com/gateleen/rule/keep-alive/1\","
                + "    \"keepAliveTimeout\": 28"
                + "  }"
                + "}";

        List<Rule> rules =  new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(rule), Router.DEFAULT_ROUTER_MULTIPLIER);
        context.assertTrue(rules.size() == 1);
        context.assertEquals(28, rules.get(0).getKeepAliveTimeout());
    }

    @Test
    public void testMissingSystemProperty(TestContext context) throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Could not resolve gateleen.missing.prop.1");

        String simpleExampleRule = "{"
                + "  \"/gateleen/rule/1\": {"
                + "    \"description\": \"Test Rule 1\","
                + "    \"url\": \"${gateleen.missing.prop.1}/gateleen/rule/1\""
                + "  },"
                + "  \"/gateleen/rule/2\": {"
                + "    \"description\": \"Test Rule 2\","
                + "    \"url\": \"${gateleen.test.prop.2}/gateleen/rule/2\""
                + "  }"
                + "}";

        properties.put("gateleen.test.prop.1", "http://someserver1/");
        properties.put("gateleen.test.prop.2", "http://someserver2/");

        new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(simpleExampleRule), Router.DEFAULT_ROUTER_MULTIPLIER);
    }

    @Test
    public void testWithMetricNameProperty(TestContext context) throws ValidationException {
        String simpleExampleRule = "{"
                + "  \"/gateleen/rule/1\": {"
                + "    \"metricName\": \"test_rule_1\","
                + "    \"description\": \"Test Rule 1\","
                + "    \"url\": \"${gateleen.test.prop.1}/gateleen/rule/1\""
                + "  },"
                + "  \"/gateleen/rule/2\": {"
                + "    \"metricName\": \"test_rule_2\","
                + "    \"description\": \"Test Rule 2\","
                + "    \"url\": \"${gateleen.test.prop.2}/gateleen/rule/2\""
                + "  }"
                + "}";

        properties.put("gateleen.test.prop.1", "http://someserver1/");
        properties.put("gateleen.test.prop.2", "http://someserver2/");
        new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(simpleExampleRule), Router.DEFAULT_ROUTER_MULTIPLIER);
    }

    @Test
    public void testNoMetricNamePropertiesDefined(TestContext context) throws ValidationException {
        String simpleExampleRule = "{"
                + "  \"/gateleen/rule/1\": {"
                + "    \"description\": \"Test Rule 1\","
                + "    \"url\": \"${gateleen.test.prop.1}/gateleen/rule/1\""
                + "  },"
                + "  \"/gateleen/rule/2\": {"
                + "    \"description\": \"Test Rule 2\","
                + "    \"url\": \"${gateleen.test.prop.2}/gateleen/rule/2\""
                + "  }"
                + "}";

        properties.put("gateleen.test.prop.1", "http://someserver1/");
        properties.put("gateleen.test.prop.2", "http://someserver2/");
        new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(simpleExampleRule), Router.DEFAULT_ROUTER_MULTIPLIER);
    }

    @Test
    public void testMixedMetricNameProperties(TestContext context) throws ValidationException {
        String simpleExampleRule = "{"
                + "  \"/gateleen/rule/1\": {"
                + "    \"description\": \"Test Rule 1\","
                + "    \"url\": \"${gateleen.test.prop.1}/gateleen/rule/1\""
                + "  },"
                + "  \"/gateleen/rule/2\": {"
                + "    \"metricName\": \"test_rule_2\","
                + "    \"description\": \"Test Rule 2\","
                + "    \"url\": \"${gateleen.test.prop.2}/gateleen/rule/2\""
                + "  }"
                + "}";

        properties.put("gateleen.test.prop.1", "http://someserver1/");
        properties.put("gateleen.test.prop.2", "http://someserver2/");
        new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(simpleExampleRule), Router.DEFAULT_ROUTER_MULTIPLIER);
    }

    @Test
    public void testMixedMetricNamePropertiesUnique(TestContext context) throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Property 'metricName' must be unique. There are multiple rules with metricName 'test_rule_2'");

        String simpleExampleRule = "{"
                + "  \"/gateleen/rule/1\": {"
                + "    \"description\": \"Test Rule 1\","
                + "    \"url\": \"${gateleen.test.prop.1}/gateleen/rule/1\""
                + "  },"
                + "  \"/gateleen/rule/2\": {"
                + "    \"metricName\": \"test_rule_2\","
                + "    \"description\": \"Test Rule 2\","
                + "    \"url\": \"${gateleen.test.prop.2}/gateleen/rule/2\""
                + "  },"
                + "  \"/gateleen/rule/3\": {"
                + "    \"metricName\": \"test_rule_2\","
                + "    \"description\": \"Test Rule 3\","
                + "    \"url\": \"${gateleen.test.prop.3}/gateleen/rule/3\""
                + "  }"
                + "}";

        properties.put("gateleen.test.prop.1", "http://someserver1/");
        properties.put("gateleen.test.prop.2", "http://someserver2/");
        properties.put("gateleen.test.prop.3", "http://someserver3/");
        new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(simpleExampleRule), Router.DEFAULT_ROUTER_MULTIPLIER);
    }

    @Test
    public void testEmptyMetricNameProperty(TestContext context) throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Validation failed");

        String simpleExampleRule = "{"
                + "  \"/gateleen/rule/1\": {"
                + "    \"metricName\": \"\","
                + "    \"description\": \"Test Rule 1\","
                + "    \"url\": \"${gateleen.test.prop.1}/gateleen/rule/1\""
                + "  },"
                + "  \"/gateleen/rule/2\": {"
                + "    \"metricName\": \"test_rule_2\","
                + "    \"description\": \"Test Rule 2\","
                + "    \"url\": \"${gateleen.test.prop.2}/gateleen/rule/2\""
                + "  }"
                + "}";

        properties.put("gateleen.test.prop.1", "http://someserver1/");
        properties.put("gateleen.test.prop.2", "http://someserver2/");
        new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(simpleExampleRule), Router.DEFAULT_ROUTER_MULTIPLIER);
    }

    @Test
    public void testMetricNamePropertyMustBeUnique(TestContext context) throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Property 'metricName' must be unique. There are multiple rules with metricName 'test_rule_1'");

        String simpleExampleRule = "{"
                + "  \"/gateleen/rule/1\": {"
                + "    \"metricName\": \"test_rule_1\","
                + "    \"description\": \"Test Rule 1\","
                + "    \"url\": \"${gateleen.test.prop.1}/gateleen/rule/1\""
                + "  },"
                + "  \"/gateleen/rule/2\": {"
                + "    \"metricName\": \"test_rule_1\","
                + "    \"description\": \"Test Rule 2\","
                + "    \"url\": \"${gateleen.test.prop.2}/gateleen/rule/2\""
                + "  }"
                + "}";

        properties.put("gateleen.test.prop.1", "http://someserver1/");
        properties.put("gateleen.test.prop.2", "http://someserver2/");
        new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(simpleExampleRule), Router.DEFAULT_ROUTER_MULTIPLIER);
    }

    @Test
    public void testExpandOnBackendRule(TestContext context) throws ValidationException {
        String expandOnBackendRule = "{"
                + "  \"/gateleen/rule/1\": {"
                + "    \"description\": \"Test Rule 1\","
                + "    \"expandOnBackend\": true,"
                + "    \"url\": \"${gateleen.test.prop.1}/gateleen/rule/1\""
                + "  }"
                + "}";

        properties.put("gateleen.test.prop.1", "http://someserver1/");

        List<Rule> rules =  new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(expandOnBackendRule), Router.DEFAULT_ROUTER_MULTIPLIER);

        context.assertTrue(rules.size() == 1);
        context.assertEquals(true, rules.get(0).isExpandOnBackend());
    }

    @Test
    public void testDeltaOnBackendRule(TestContext context) throws ValidationException {
        String deltaOnBackendRule = "{"
                + "  \"/gateleen/rule/1\": {"
                + "    \"description\": \"Test Rule 1\","
                + "    \"deltaOnBackend\": true,"
                + "    \"url\": \"${gateleen.test.prop.1}/gateleen/rule/1\""
                + "  }"
                + "}";

        properties.put("gateleen.test.prop.1", "http://someserver1/");

        List<Rule> rules =  new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(deltaOnBackendRule), Router.DEFAULT_ROUTER_MULTIPLIER);

        context.assertTrue(rules.size() == 1);
        context.assertEquals(true, rules.get(0).isDeltaOnBackend());
    }

    @Test
    public void testStorageExpandRule(TestContext context) throws ValidationException {
        String storageExpandRule = "{" +
                " \"/gateleen/rule/1\": {" +
                "  \"description\": \"Test Rule 1\"," +
                "  \"path\": \"/${gateleen.test.prop.1}/gateleen/rule/1\"," +
                "  \"storageExpand\": true," +
                "  \"storage\": \"main\"" +
                " }," +
                " \"/gateleen/rule/2\": {" +
                "  \"description\": \"Test Rule 2\"," +
                "  \"path\": \"/${gateleen.test.prop.1}/gateleen/rule/2\"," +
                "  \"storageExpand\": false," +
                "  \"storage\": \"main\"" +
                " }," +
                " \"/gateleen/rule/3\": {" +
                "  \"description\": \"Test Rule 3\"," +
                "  \"path\": \"/${gateleen.test.prop.1}/gateleen/rule/3\"," +
                "  \"storage\": \"main\"" +
                " }" +
                "}";

        properties.put("gateleen.test.prop.1", "http://someserver1/");

        List<Rule> rules =  new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(storageExpandRule), Router.DEFAULT_ROUTER_MULTIPLIER);

        context.assertEquals(3, rules.size());
        context.assertTrue(rules.get(0).isStorageExpand(), "Rule has property 'storageExpand' with value true. So isStorageExpand() should return true");
        context.assertFalse(rules.get(1).isStorageExpand(), "Rule has property 'storageExpand' with value false. So isStorageExpand() should return false");
        context.assertFalse(rules.get(2).isStorageExpand(), "Rule has no property 'storageExpand'. So isStorageExpand() should return false");
    }

    @Test
    public void testAdditionalPropertyNotAllowed() throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Validation failed");

        String rules = "{" +
                " \"/gateleen/rule/1\": {" +
                "  \"description\": \"Test Rule 1\"," +
                "  \"path\": \"/${gateleen.test.prop.1}/gateleen/rule/1\"," +
                "  \"storageExpand\": true," +
                "  \"storage\": \"main\"" +
                " }," +
                " \"/gateleen/rule/2\": {" +
                "  \"description\": \"Test Rule 2\"," +
                "  \"path\": \"/${gateleen.test.prop.1}/gateleen/rule/2\"," +
                "  \"storageExpand\": false," +
                "  \"storage\": \"main\"" +
                " }," +
                " \"/gateleen/rule/3\": {" +
                "  \"description\": \"Test Rule 3\"," +
                "  \"path\": \"/${gateleen.test.prop.1}/gateleen/rule/3\"," +
                "  \"anAdditionalPropetyThatsNotAllowed\": 123456," +
                "  \"storage\": \"main\"" +
                " }" +
                "}";

        properties.put("gateleen.test.prop.1", "http://someserver1/");
        new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(rules), Router.DEFAULT_ROUTER_MULTIPLIER);
    }

    @Test
    public void testStoragePropertyWrongFormat() throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Validation failed");

        String rules = "{" +
                " \"/gateleen/rule/1\": {" +
                "  \"description\": \"Test Rule 1\"," +
                "  \"path\": \"/${gateleen.test.prop.1}/gateleen/rule/1\"," +
                "  \"storageExpand\": true," +
                "  \"storage\": 123" +
                " }" +
                "}";

        properties.put("gateleen.test.prop.1", "http://someserver1/");
        new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(rules), Router.DEFAULT_ROUTER_MULTIPLIER);
    }

    @Test
    public void testMissingPathProperty() throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("For storage routing, 'path' must be specified.");

        String rules = "{" +
                " \"/gateleen/rule/1\": {" +
                "  \"description\": \"Test Rule 1\"," +
                "  \"storage\": \"main\"" +
                " }" +
                "}";

        properties.put("gateleen.test.prop.1", "http://someserver1/");
        new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(rules), Router.DEFAULT_ROUTER_MULTIPLIER);
    }

    @Test
    public void testUrlAndPathProperty() throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Either 'url' or 'path' must be given, not both");

        String rules = "{" +
                " \"/gateleen/rule/1\": {" +
                "  \"description\": \"Test Rule 1\"," +
                "  \"path\": \"/${gateleen.test.prop.1}/gateleen/rule/1\"," +
                "  \"url\": \"${gateleen.test.prop.1}/gateleen/rule/1\"," +
                "  \"storage\": \"main\"" +
                " }" +
                "}";

        properties.put("gateleen.test.prop.1", "http://someserver1/");
        new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(rules), Router.DEFAULT_ROUTER_MULTIPLIER);
    }

    @Test
    public void testInvalidUrlPattern() throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Invalid url for pattern");

        String rules = "{" +
                " \"/gateleen/rule/1\": {" +
                "  \"description\": \"Test Rule 1\"," +
                "  \"url\": \"${gateleen.test.prop.1}/gateleen/rule/1\"" +
                " }" +
                "}";

        properties.put("gateleen.test.prop.1", "http://someserver1:1234:1234/"); // port information is not valid
        new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(rules), Router.DEFAULT_ROUTER_MULTIPLIER);
    }

    @Test
    public void testMissingLeadingSlashForPathProperty() throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Illegal value for 'path', it must be a path starting with slash");

        String rules = "{" +
                " \"/gateleen/rule/1\": {" +
                "  \"description\": \"Test Rule 1\"," +
                "  \"path\": \"${gateleen.test.prop.1}/gateleen/rule/1\"," +
                "  \"storage\": \"main\"" +
                " }" +
                "}";

        properties.put("gateleen.test.prop.1", "http://someserver1/");
        new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(rules), Router.DEFAULT_ROUTER_MULTIPLIER);
    }

    @Test
    public void testInvalidJson() throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Unable to parse json");

        String rules = "" +
                " \"/gateleen/rule/1\": {" +
                "  \"description\": \"Test Rule 1\"," +
                "  \"path\": \"/${gateleen.test.prop.1}/gateleen/rule/1\"," +
                "  \"storageExpand\": true," +
                "  \"storage\": 123" +
                " }" +
                "}";

        properties.put("gateleen.test.prop.1", "http://someserver1/");
        new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(rules), Router.DEFAULT_ROUTER_MULTIPLIER);
    }

    @Test
    public void testInvalidProxyOptions() throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Validation failed");

        String rules = "{\n" +
                "  \"/gateleen/rule/1\": {\n" +
                "    \"description\": \"Test rule 1\",\n" +
                "    \"proxyOptions\": {\n" +
                "      \"type\": \"not_supported_type\",\n" +
                "      \"host\": \"dd\",\n" +
                "      \"port\": 123\n" +
                "    }\n" +
                "  }\n" +
                "}";

        new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(rules), Router.DEFAULT_ROUTER_MULTIPLIER);
    }

    @Test
    public void testInvalidProxyOptionsSinceHostIsMissing() throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Validation failed");

        String rules = "{\n" +
                "  \"/gateleen/rule/1\": {\n" +
                "    \"description\": \"Test rule 1\",\n" +
                "    \"proxyOptions\": {\n" +
                "      \"port\": 123\n" +
                "    }\n" +
                "  }\n" +
                "}";

        new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(rules), Router.DEFAULT_ROUTER_MULTIPLIER);
    }

    @Test
    public void testValidProxyOptions(TestContext context) throws ValidationException {

        String rules = "{\n" +
                "  \"/gateleen/rule/1\": {\n" +
                "    \"description\": \"Test rule 1\",\n" +
                "    \"proxyOptions\": {\n" +
                "      \"type\": \"HTTP\",\n" +
                "      \"host\": \"someHost\",\n" +
                "      \"port\": 1234\n" +
                "    }\n" +
                "  },\n" +
                "  \"/gateleen/rule/2\": {\n" +
                "    \"description\": \"Test rule 2\",\n" +
                "    \"proxyOptions\": {\n" +
                "      \"type\": \"SOCKS5\",\n" +
                "      \"host\": \"someHost\",\n" +
                "      \"port\": 1234,\n" +
                "      \"username\": \"johndoe\",\n" +
                "      \"password\": \"secret\"\n" +
                "    }\n" +
                "  },\n" +
                "  \"/gateleen/rule/3\": {\n" +
                "    \"description\": \"Test rule 3\",\n" +
                "    \"proxyOptions\": {\n" +
                "      \"host\": \"someOtherHost\",\n" +
                "      \"port\": 5678\n" +
                "    }\n" +
                "  },\n" +
                "  \"/gateleen/rule/4\": {\n" +
                "    \"description\": \"Test rule 4 (without proxyOptions)\"\n" +
                "  }\n" +
                "}";

        List<Rule> rulesList = new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(rules), Router.DEFAULT_ROUTER_MULTIPLIER);

        context.assertEquals(4, rulesList.size());

        context.assertNotNull(rulesList.get(0).getProxyOptions());
        context.assertEquals(ProxyType.HTTP, rulesList.get(0).getProxyOptions().getType());
        context.assertEquals("someHost", rulesList.get(0).getProxyOptions().getHost());
        context.assertEquals(1234, rulesList.get(0).getProxyOptions().getPort());
        context.assertNull(rulesList.get(0).getProxyOptions().getUsername());
        context.assertNull(rulesList.get(0).getProxyOptions().getPassword());

        context.assertNotNull(rulesList.get(1).getProxyOptions());
        context.assertEquals(ProxyType.SOCKS5, rulesList.get(1).getProxyOptions().getType());
        context.assertEquals("someHost", rulesList.get(1).getProxyOptions().getHost());
        context.assertEquals(1234, rulesList.get(1).getProxyOptions().getPort());
        context.assertEquals("johndoe", rulesList.get(1).getProxyOptions().getUsername());
        context.assertEquals("secret", rulesList.get(1).getProxyOptions().getPassword());

        context.assertNotNull(rulesList.get(2).getProxyOptions());
        context.assertEquals(ProxyType.HTTP, rulesList.get(2).getProxyOptions().getType()); // this is the default value
        context.assertEquals("someOtherHost", rulesList.get(2).getProxyOptions().getHost());
        context.assertEquals(5678, rulesList.get(2).getProxyOptions().getPort());
        context.assertNull(rulesList.get(2).getProxyOptions().getUsername());
        context.assertNull(rulesList.get(2).getProxyOptions().getPassword());

        context.assertNull(rulesList.get(3).getProxyOptions());
    }

    @Test
    public void testStaticPortConfig(TestContext context) throws ValidationException {
        String simpleExampleRule = "{"
                + "  \"/gateleen/rule/1\": {"
                + "    \"description\": \"Test Rule 1\","
                + "    \"url\": \"http://someserver1:1234/gateleen/rule/1\""
                + "  },"
                + "  \"/gateleen/rule/2\": {"
                + "    \"description\": \"Test Rule 2\","
                + "    \"url\": \"http://someserver2:5678/gateleen/rule/2\""
                + "  }"
                + "}";

        List<Rule> rules =  new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(simpleExampleRule), Router.DEFAULT_ROUTER_MULTIPLIER);

        context.assertTrue(rules.size() == 2);
        context.assertEquals("someserver1", rules.get(0).getHost());
        context.assertEquals(1234, rules.get(0).getPort());
        context.assertFalse(rules.get(0).hasPortWildcard());
        context.assertEquals("someserver2", rules.get(1).getHost());
        context.assertEquals(5678, rules.get(1).getPort());
        context.assertFalse(rules.get(1).hasPortWildcard());
    }

    @Test
    public void testValidDynamicPortConfig(TestContext context) throws ValidationException {
        String simpleExampleRule = "{\n" +
                "  \"/gateleen/(1234|5678)/rule/(.*)\": {\n" +
                "    \"url\": \"http://someserver1:$1/target/$2\"\n" +
                "  }\n" +
                "}";

        List<Rule> rules =  new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(simpleExampleRule), Router.DEFAULT_ROUTER_MULTIPLIER);

        context.assertTrue(rules.size() == 1);
        context.assertEquals("someserver1", rules.get(0).getHost());
        context.assertTrue(rules.get(0).hasPortWildcard());
        context.assertEquals("$1", rules.get(0).getPortWildcard());
    }

    @Test
    public void testInvalidDynamicPortConfig(TestContext context) throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Invalid url for pattern /gateleen/(1234|5678)/rule/(.*): http://someserver1:xxx/target/$2");

        String simpleExampleRule = "{\n" +
                "  \"/gateleen/(1234|5678)/rule/(.*)\": {\n" +
                "    \"url\": \"http://someserver1:xxx/target/$2\"\n" +
                "  }\n" +
                "}";

        new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(simpleExampleRule), Router.DEFAULT_ROUTER_MULTIPLIER);
    }

    @Test
    public void testInvalidWildcardDynamicPortConfig(TestContext context) throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Invalid url for pattern /gateleen/(1234|5678)/rule/(.*): http://someserver1:$abc/target/$2");

        String simpleExampleRule = "{\n" +
                "  \"/gateleen/(1234|5678)/rule/(.*)\": {\n" +
                "    \"url\": \"http://someserver1:$abc/target/$2\"\n" +
                "  }\n" +
                "}";

        new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(simpleExampleRule), Router.DEFAULT_ROUTER_MULTIPLIER);
    }

    @Test
    public void testInvalidTranslateStatus() throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Validation failed");

        String rules = "{\n" +
                "  \"/gateleen/rule/1\": {\n" +
                "    \"description\": \"Test rule 1\",\n" +
                "    \"translateStatus\": {\n" +
                "      \"400\": 200.99\n" +
                "    }\n" +
                "  }\n" +
                "}";

        new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(rules), Router.DEFAULT_ROUTER_MULTIPLIER);
    }

    @Test
    public void testValidTranslateStatus(TestContext context) throws ValidationException {
        String validRule = "{\n" +
                "  \"/gateleen/rule/1\": {\n" +
                "    \"description\": \"Test rule 1\",\n" +
                "    \"translateStatus\": {\n" +
                "      \"400\": 200\n" +
                "    }\n" +
                "  }\n" +
                "}";

        List<Rule> rules =  new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(validRule), Router.DEFAULT_ROUTER_MULTIPLIER);

        context.assertTrue(rules.size() == 1);

        Map<Pattern, Integer> translateStatus = rules.get(0).getTranslateStatus();
        context.assertEquals(1, translateStatus.size());
        for (Integer value : translateStatus.values()) {
            context.assertEquals(200, value);
        }
    }

    @Test
    public void testMethodsOnly(TestContext context) throws ValidationException {
        String rule = "{\n" +
                "  \"/gateleen/rule/1\": {\n" +
                "    \"description\": \"Test rule 1\",\n" +
                "    \"methods\": [\"GET\", \"PUT\"]\n" +
                "  }\n" +
                "}";

        List<Rule> rules =  new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(rule), Router.DEFAULT_ROUTER_MULTIPLIER);

        context.assertTrue(rules.size() == 1);
        context.assertTrue(rules.get(0).getMethods().length == 2);
    }

    @Test
    public void testHeadersFilterEmptyNotValid(TestContext context) throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Validation failed");

        String rule = "{\n" +
                "  \"/gateleen/rule/1\":  {\n" +
                "    \"description\": \"Test rule 1\",\n" +
                "    \"headersFilter\": \"\"\n" +
                "  }\n" +
                "}";

        new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(rule), Router.DEFAULT_ROUTER_MULTIPLIER);
    }

    @Test
    public void testHeadersFilterNotValid(TestContext context) throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Failed to parse regex pattern 'x-(foobar: true'.");

        String rule = "{\n" +
                "  \"/gateleen/rule/1\":  {\n" +
                "    \"description\": \"Test rule 1\",\n" +
                "    \"headersFilter\": \"x-(foobar: true\"\n" +
                "  }\n" +
                "}";

        new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(rule), Router.DEFAULT_ROUTER_MULTIPLIER);
    }

    @Test
    public void testHeadersFilterValid(TestContext context) throws ValidationException {
        String rule = "{\n" +
                "  \"/gateleen/rule/1\":  {\n" +
                "    \"description\": \"Test rule 1\",\n" +
                "    \"headersFilter\": \"x-foobar: true\"\n" +
                "  }\n" +
                "}";

        List<Rule> rules = new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(rule), Router.DEFAULT_ROUTER_MULTIPLIER);

        context.assertTrue(rules.size() == 1);
        context.assertEquals(Pattern.compile("x-foobar: true").pattern(), rules.get(0).getHeadersFilterPattern().pattern());
    }

    @Test
    public void testConnectionPool(TestContext context) throws ValidationException {
        String rule = "{\n" +
                "  \"/gateleen/rule/1\":  {\n" +
                "    \"description\": \"Test rule 1\",\n" +
                "    \"headersFilter\": \"x-foobar: true\",\n" +
                "    \"connectionPoolSize\": 10\n" +
                "  }\n" +
                "}";

        List<Rule> rules = new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(rule), Router.DEFAULT_ROUTER_MULTIPLIER);

        context.assertTrue(rules.size() == 1);
        context.assertEquals(10, rules.get(0).getPoolSize());

        rules = new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(rule), 2);

        context.assertTrue(rules.size() == 1);
        context.assertEquals(5, rules.get(0).getPoolSize());

        rules = new RuleFactory(properties, routingRulesSchema).parseRules(Buffer.buffer(rule), 3);

        context.assertTrue(rules.size() == 1);
        context.assertEquals(3, rules.get(0).getPoolSize());
    }
}

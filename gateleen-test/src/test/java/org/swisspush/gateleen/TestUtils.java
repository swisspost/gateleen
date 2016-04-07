package org.swisspush.gateleen;

import com.google.common.collect.ImmutableMap;
import com.jayway.awaitility.Duration;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map.Entry;
import java.util.Set;

import static com.jayway.awaitility.Awaitility.await;
import static com.jayway.restassured.RestAssured.given;
import static com.jayway.restassured.RestAssured.when;
import static org.hamcrest.CoreMatchers.equalTo;

public class TestUtils {
    private static final Logger LOG = LoggerFactory.getLogger(TestUtils.class);

    /**
     * Puts the given routing on the server and waits
     * a few seconds.
     * 
     * @param routing the content (json) of the routing to be put
     */
    public static void putRoutingRules(JsonObject routing) {
        System.out.println(routing.toString());

        given().body(routing.toString()).put("http://localhost:" + AbstractTest.MAIN_PORT + AbstractTest.SERVER_ROOT + "/admin/v1/routing/rules").then().assertThat().statusCode(200);

        // wait for the routing to take effect
        waitSomeTime(3);
    }

    /**
     * Adds the routing rule for hooking to the given routing rules.
     * 
     * @param rules current rules.
     */
    public static JsonObject addRoutingRuleHooks(JsonObject rules) {
        JsonObject nullForwarder = createRoutingRule(ImmutableMap.of(
                "name",
                "null_forwarder_rule",
                "description",
                "Null destination"));
        rules = addRoutingRule(rules, AbstractTest.SERVER_ROOT + "/null/?.*", nullForwarder);

        JsonObject hooks = createRoutingRule(ImmutableMap.of(
                "name",
                "hooks_rule",
                "description",
                "Null destination"));
        rules = addRoutingRule(rules, AbstractTest.SERVER_ROOT + "/hooks/v1/listeners/?.*", hooks);

        return rules;
    }

    /**
     * Adds the routing rule for the main storage to the given routing rules.
     * 
     * @param rules current rules.
     */
    public static JsonObject addRoutingRuleMainStorage(JsonObject rules) {
        JsonObject mstorage = createRoutingRule(ImmutableMap.of(
                "name",
                "main_storage_rule",
                "description",
                "a default routing for the source storage",
                "path",
                "/$1",
                "storage",
                "main",
                "logExpiry",
                0));

        rules = addRoutingRule(rules, "/(.*)", mstorage);

        return rules;
    }

    /**
     * Returns the hook route url suffix, which marks
     * a resource to be routed.
     * return the hook route url suffix
     */
    public static String getHookRouteUrlSuffix() {
        return "/_hooks/route";
    }

    /**
     * Returns the hook listeners url suffix, which marks
     * a resource to be listend to.
     * 
     * @return the hook listeners url suffix
     */
    public static String getHookListenersUrlSuffix() {
        return "/_hooks/listeners/http/";
    }

    /**
     * Waits a few seconds.
     * 
     * @param seconds amount of time to wait.
     */
    public static void waitSomeTime(int seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException e) {
        }
    }

    /**
     * Adds the routing rule for cleanup to the given routing rules.
     * 
     * @param rules current rules.
     */
    public static JsonObject addRoutingRuleCleanup(JsonObject rules) {

        JsonObject cleanup = createRoutingRule(ImmutableMap.of(
                "name",
                "cleanup_rule",
                "description",
                "enhanced timeout for cleaning up the resources",
                "url",
                "http://localhost:" + RedisEmbeddedConfiguration.REDIS_PORT + AbstractTest.SERVER_ROOT + "/_cleanup",
                "timeout",
                120));

        rules = addRoutingRule(rules, AbstractTest.SERVER_ROOT + "/_cleanup", cleanup);
        return rules;
    }

    /**
     * Adds a new routing rule (in first position) the routing rules.
     *
     * @param originalRoutingRules The original rule set
     * @param newRoutingRuleName The name of the new rule to add
     * @param newRoutingRule The new rule to add
     * @return the enhanced rules
     */
    public static JsonObject addRoutingRule(JsonObject originalRoutingRules, String newRoutingRuleName, JsonObject newRoutingRule) {
        Set<String> originalRulesFieldNames = originalRoutingRules.fieldNames();
        JsonObject extendedRules = new JsonObject();
        extendedRules.put(newRoutingRuleName, newRoutingRule);
        for (String fieldName : originalRulesFieldNames) {
            extendedRules.put(fieldName, originalRoutingRules.getJsonObject(fieldName));
        }
        return extendedRules;
    }

    /**
     * Creates a new routing rule from the provided ruleProperties map.
     * <p>
     * Sample usage:
     * 
     * <pre>
     * <code>TestUtil.createRoutingRule(ImmutableMap.of(
     *      "description",
     *      "Description of the rule",
     *      "url",
     *      "http://localhost:8989/gateleen/server/tests/$1"));</code>
     * </pre>
     * </p>
     * 
     * @param map the properties of the new rule key=property name, value=property value
     * @return the new rule as a json object
     */
    public static JsonObject createRoutingRule(ImmutableMap<String, ? extends Object> map) {
        JsonObject newRule = new JsonObject();
        for (Entry<String, ? extends Object> entry : map.entrySet()) {
            if (entry.getValue() instanceof String) {
                newRule.put(entry.getKey(), (String) entry.getValue());
            } else if (entry.getValue() instanceof Number) {
                newRule.put(entry.getKey(), entry.getValue());
            } else if (entry.getValue() instanceof Boolean) {
                newRule.put(entry.getKey(), (Boolean)entry.getValue());
            } else if (entry.getValue() instanceof JsonObject) {
                newRule.put(entry.getKey(), (JsonObject) entry.getValue());
            } else if (entry.getValue() instanceof JsonArray) {
                newRule.put(entry.getKey(), (JsonArray) entry.getValue());
            } else {
                LOG.error("not handled data type for rule " + entry.getKey());
            }
        }
        return newRule;
    }

    /**
     * Checks if the GET request for the
     * resource gets a response with
     * the given status code in 5 sec.
     * 
     * @param request
     * @param statusCode
     */
    public static void checkGETStatusCodeWithAwait(final String request, final Integer statusCode) {
        await().atMost(Duration.FIVE_SECONDS).until(() -> String.valueOf(when().get(request).getStatusCode()), equalTo(String.valueOf(statusCode)));
    }
}

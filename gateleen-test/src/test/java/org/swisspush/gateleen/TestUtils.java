package org.swisspush.gateleen;

import com.google.common.collect.ImmutableMap;
import com.jayway.awaitility.Duration;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.hook.HookHandler;
import org.swisspush.gateleen.hook.HookTriggerType;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static com.jayway.awaitility.Awaitility.await;
import static com.jayway.restassured.RestAssured.*;
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
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
        }
        // 13m20s with wait 3   seconds for whole test suite
        //  9m15s with wait 0.5 seconds for whole test suite
    }

    /**
     * Adds the routing rule for hooking to the given routing rules.
     * 
     * @param rules current rules.
     */
    public static JsonObject addRoutingRuleHooks(JsonObject rules) {
        JsonObject nullForwarder = createRoutingRule(ImmutableMap.of(
                "description",
                "Null destination"));
        rules = addRoutingRule(rules, AbstractTest.SERVER_ROOT + "/null/?.*", nullForwarder);

        JsonObject hooks = createRoutingRule(ImmutableMap.of(
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
                "description",
                "enhanced timeout for cleaning up the resources",
                "url",
                "http://localhost:" + AbstractTest.REDIS_PORT + AbstractTest.SERVER_ROOT + "/_cleanup",
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

    /**
     * Registers a route.
     *
     * @param requestUrl
     * @param target
     * @param methods
     */
    public static void registerRoute(final String requestUrl, final String target, String[] methods) {
        registerRoute(requestUrl, target, methods, null);
    }
    public static void registerRoute(final String requestUrl, final String target, String[] methods, Map<String, String> staticHeaders) {
        registerRoute(requestUrl, target, methods, staticHeaders, true, false);
    }

        /**
         * Registers a route.
         *
         * @param requestUrl
         * @param target
         * @param methods
         * @param staticHeaders
         */
    public static void registerRoute(final String requestUrl, final String target, String[] methods, Map<String, String> staticHeaders, boolean collection, boolean listable) {
        String body = "{ \"destination\":\"" + target + "\"";

        String m = null;
        if (methods != null) {
            for (String method : methods) {
                m += "\"" + method + "\", ";
            }
            m = m.endsWith(", ") ? m.substring(0, m.lastIndexOf(",")) : m;
            m = "\"methods\": [" + m + "]";
        }

        if ( staticHeaders != null && staticHeaders.size() > 0 ) {
            body = body + ", \"staticHeaders\" : {";

            boolean notFirst = false;
            for (Map.Entry<String, String> entry : staticHeaders.entrySet() ) {
                body = body + ( notFirst ? ", " : "" ) + "\"" + entry.getKey() + "\" : \"" + entry.getValue() + "\"";

                if ( ! notFirst ) {
                    notFirst = true;
                }
            }

            body = body + "}";
        }

        body += ", " + "\"collection\":" + collection;
        body += ", " + "\"listable\":" + listable;

        body = body + "}";

        with().body(body).put(requestUrl).then().assertThat().statusCode(200);
    }

    /**
     * Unregisters a route.
     *
     * @param request
     */
    public static void unregisterRoute(String request) {
        delete(request).then().assertThat().statusCode(200);
    }


    /**
     * Registers a listener.
     *  @param requestUrl
     * @param target
     * @param methods
     */
    public static void registerListener(final String requestUrl, final String target, String[] methods) {
        registerListener(requestUrl, target, methods, null);
    }

    /**
     * Registers a listener.
     *  @param requestUrl
     * @param target
     * @param methods
     * @param filter
     */
    public static void registerListener(final String requestUrl, final String target, String[] methods, String filter) {
        registerListener(requestUrl, target, methods, filter, null);
    }

    /**
     * Registers a listener with a filter.
     *  @param requestUrl
     * @param target
     * @param methods
     * @param filter
     * @param queueExpireTime
     */
    public static void registerListener(final String requestUrl, final String target, String[] methods, String filter, Integer queueExpireTime) {
        registerListener(requestUrl, target, methods, filter, queueExpireTime, null);
    }

    /**
     * Registers a listener with a filter and static headers.
     *  @param requestUrl
     * @param target
     * @param methods
     * @param filter
     * @param queueExpireTime
     * @param staticHeaders
     */
    public static void registerListener(final String requestUrl, final String target, String[] methods, String filter, Integer queueExpireTime, Map<String, String> staticHeaders) {
        registerListener(requestUrl, target, methods, filter, queueExpireTime, staticHeaders, null);
    }

    /**
     * Registers a listener with a filter, static headers and a event trigger.
     *  @param requestUrl
     * @param target
     * @param methods
     * @param filter
     * @param queueExpireTime
     * @param staticHeaders
     */
    public static void registerListener(final String requestUrl, final String target, String[] methods, String filter, Integer queueExpireTime, Map<String, String> staticHeaders, HookTriggerType type) {
        String body = "{ \"destination\":\"" + target + "\"";

        if (methods != null) {
            String m = "";
            for (String method : methods) {
                m += "\"" + method + "\", ";
            }
            m = m.endsWith(", ") ? m.substring(0, m.lastIndexOf(",")) : m;
            m = ",\"methods\": [" + m + "]";
            body += m;
        }
        body += queueExpireTime != null ? ", \""+ HookHandler.QUEUE_EXPIRE_AFTER + "\" : " + queueExpireTime : "";
        body += filter != null ? ", \"filter\" : \"" + filter + "\"" : "";
        body += type != null ? ", \"type\" : \"" + type.text() + "\"" : "";


        if ( staticHeaders != null && staticHeaders.size() > 0 ) {
            body = body + ", \"staticHeaders\" : {";

            boolean notFirst = false;
            for (Map.Entry<String, String> entry : staticHeaders.entrySet() ) {
                body = body + ( notFirst ? ", " : "" ) + "\"" + entry.getKey() + "\" : \"" + entry.getValue() + "\"";

                if ( ! notFirst ) {
                    notFirst = true;
                }
            }

            body = body + "}";
        }

        body = body + "}";

        with().body(body).put(requestUrl).then().assertThat().statusCode(200);
        waitSomeTime(1);
    }

    /**
     * Unregisters a listener.
     *
     * @param request
     */
    public static void unregisterListener(String request) {
        delete(request).then().assertThat().statusCode(200);
    }
}

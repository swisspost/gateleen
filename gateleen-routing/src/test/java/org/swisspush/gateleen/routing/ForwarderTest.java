package org.swisspush.gateleen.routing;

import com.google.common.collect.ImmutableMap;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;

import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.HeaderFunction;
import org.swisspush.gateleen.core.http.HeaderFunctions;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.monitoring.MonitoringHandler;

/**
 * Tests for the Forwarder class
 */
@RunWith(VertxUnitRunner.class)
public class ForwarderTest {

    private Vertx vertx;
    private LoggingResourceManager loggingResourceManager;
    private MonitoringHandler monitoringHandler;
    private HttpClient httpClient;
    private ResourceStorage storage;
    private Logger logger;

    private static final String DEFAULTHOST = "defaultHost";
    private static final int DEFAULTPORT = 1234;
    private static final String HOST_HEADER = "Host";
    private static final String HOST_DEFAULT = DEFAULTHOST + ":" + DEFAULTPORT;
    private static final String HOST_OLD = "oldHost:123";
    private static final String HOST_NEW = "newHost:123";
    private static final String SERVER_URL = "/gateleen/server";
    private static final String RULES_PATH = SERVER_URL + "/admin/v1/routing/rules";
    private static final String USER_PROFILE_PATH = SERVER_URL + "/users/v1/%s/profile";

    private static final String RULES = "{\n"
            + "  \"/ruleWithoutHeader\": {\n"
            + "    \"description\": \"Test Rule 1\",\n"
            + "    \"url\": \"/gateleen/rule/1\"\n"
            + "  },\n"
            + "  \"/ruleWithHostHeader\": {\n"
            + "    \"description\": \"Test Rule 2\",\n"
            + "    \"url\": \"/gateleen/rule/2\",\n"
            + "    \"headers\": [{ \"header\": \"" + HOST_HEADER + "\" , \"value\": \"" + HOST_NEW + "\"}]"
            + "  }\n"
            + "}";

    private static Rule extractRule(String rulePattern) {
        Rule rule = new Rule();
        JsonObject rules = new JsonObject(RULES);
        JsonObject extractedRule = rules.getJsonObject(rulePattern);
        JsonArray headers = extractedRule.getJsonArray("headers");
        if (headers != null) {
            HeaderFunction headerFunction = HeaderFunctions.parseFromJson(headers);
            rule.setHeaderFunction(headerFunction);
        }
        rule.setUrlPattern(extractedRule.getString("url"));
        rule.setHost(DEFAULTHOST);
        rule.setPort(DEFAULTPORT);
        return rule;
    }

    @Before
    public void setUp() {
        vertx = Mockito.mock(Vertx.class);
        loggingResourceManager = Mockito.mock(LoggingResourceManager.class);
        monitoringHandler = Mockito.mock(MonitoringHandler.class);
        httpClient = Mockito.mock(HttpClient.class);
        storage = new MockResourceStorage(ImmutableMap.of(RULES_PATH, RULES));
        logger = LoggerFactory.getLogger(ForwarderTest.class);
    }

    @Test
    public void testHeaderFunctionsGivenHostUpdatedByConfiguredRuleHostHeader() {
        Rule rule = extractRule("/ruleWithHostHeader");
        Forwarder forwarder = new Forwarder(vertx, httpClient, rule, storage, loggingResourceManager,
                monitoringHandler, USER_PROFILE_PATH);
        MultiMap reqHeaders = new HeadersMultiMap();
        reqHeaders.add(HOST_HEADER, HOST_OLD);
        String errorMessage = forwarder.applyHeaderFunctions(logger, reqHeaders);
        Assert.assertNull(errorMessage);
        Assert.assertEquals(HOST_NEW, reqHeaders.get(HOST_HEADER));
    }

    @Test
    public void testHeaderFunctionsDefaultHostHeaderUpdatedByConfiguredRuleHostHeader() {
        Rule rule = extractRule("/ruleWithHostHeader");
        Forwarder forwarder = new Forwarder(vertx, httpClient, rule, storage, loggingResourceManager,
                monitoringHandler, USER_PROFILE_PATH);
        MultiMap reqHeaders = new HeadersMultiMap();
        reqHeaders.add(HOST_HEADER, HOST_DEFAULT);
        String errorMessage = forwarder.applyHeaderFunctions(logger, reqHeaders);
        Assert.assertNull(errorMessage);
        Assert.assertEquals(HOST_NEW, reqHeaders.get(HOST_HEADER));
    }

    /**
     * This is a very special case which should not happen in reality with a proper rule configuration.
     * As we do not want to care about the incoming header (from outside) we assume that it must be always
     * the default host header coming in and therefore it will be reset to the default header anyway if it will not be
     * the case.
     *
     * This could happen therefore only if the configured rule host header would match the default one and this
     * would not make sense to be configured like that as it would result in the same as configuring no host header.
     */
    @Test
    public void testHeaderFunctionsGivenHostHeaderUpdatedToDefaultHostHeaderWhenEqualToConfiguredRuleHostHeader() {
        Rule rule = extractRule("/ruleWithHostHeader");
        Forwarder forwarder = new Forwarder(vertx, httpClient, rule, storage, loggingResourceManager,
                monitoringHandler, USER_PROFILE_PATH);
        MultiMap reqHeaders = new HeadersMultiMap();
        reqHeaders.add(HOST_HEADER, HOST_NEW);
        String errorMessage = forwarder.applyHeaderFunctions(logger, reqHeaders);
        Assert.assertNull(errorMessage);
        Assert.assertEquals(HOST_DEFAULT, reqHeaders.get(HOST_HEADER));
    }

    @Test
    public void testHeaderFunctionsGivenHostHeaderUpdatedByDefaultHostHeaderWhenNoConfiguredRuleHostHeader() {
        Rule rule = extractRule("/ruleWithoutHeader");
        Forwarder forwarder = new Forwarder(vertx, httpClient, rule, storage, loggingResourceManager,
                monitoringHandler, USER_PROFILE_PATH);
        MultiMap reqHeaders = new HeadersMultiMap();
        reqHeaders.add(HOST_HEADER, HOST_OLD);
        String errorMessage = forwarder.applyHeaderFunctions(logger, reqHeaders);
        Assert.assertNull(errorMessage);
        Assert.assertEquals(HOST_DEFAULT, reqHeaders.get(HOST_HEADER));
    }

    @Test
    public void testHeaderFunctionsGivenDefaultHostHeaderRemainsWhenNoConfiguredRuleHostHeader() {
        Rule rule = extractRule("/ruleWithoutHeader");
        Forwarder forwarder = new Forwarder(vertx, httpClient, rule, storage, loggingResourceManager,
                monitoringHandler, USER_PROFILE_PATH);
        MultiMap reqHeaders = new HeadersMultiMap();
        reqHeaders.add(HOST_HEADER, HOST_DEFAULT);
        String errorMessage = forwarder.applyHeaderFunctions(logger, reqHeaders);
        Assert.assertNull(errorMessage);
        Assert.assertEquals(HOST_DEFAULT, reqHeaders.get(HOST_HEADER));
    }

}
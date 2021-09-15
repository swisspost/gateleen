package org.swisspush.gateleen.routing;

import com.google.common.collect.ImmutableMap;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.impl.headers.VertxHttpHeaders;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
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
  private String serverUrl;
  private String rulesPath;
  private String userProfilePath;
  private ResourceStorage storage;
  private Logger logger;

  private final static String DEFAULTHOST = "DEFAULTHOST";
  private final static int DEFAULTPORT = 1234;
  private static final String HOST_HEADER = "Host";

  private static final String RULES = "{\n"
      + "  \"/ruleWithoutHeader\": {\n"
      + "    \"description\": \"Test Rule 1\",\n"
      + "    \"url\": \"/gateleen/rule/1\"\n"
      + "  },\n"
      + "  \"/ruleWithHostHeader\": {\n"
      + "    \"description\": \"Test Rule 2\",\n"
      + "    \"url\": \"/gateleen/rule/2\",\n"
      + "    \"headers\": [{ \"header\": \"Host\" , \"value\": \"newHost\"}]"
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
    serverUrl = "/gateleen/server";
    rulesPath = serverUrl + "/admin/v1/routing/rules";
    userProfilePath = serverUrl + "/users/v1/%s/profile";
    storage = new MockResourceStorage(ImmutableMap.of(rulesPath, RULES));
    logger = LoggerFactory.getLogger(ForwarderTest.class.getName());
  }

  @Test
  public void testHeaderFunctionsWithHostHeader(TestContext context) {
    Rule rule = extractRule("/ruleWithHostHeader");
    Forwarder forwarder = new Forwarder(vertx, httpClient, rule, storage, loggingResourceManager,
        monitoringHandler, userProfilePath);
    MultiMap reqHeaders = new VertxHttpHeaders();
    reqHeaders.add(HOST_HEADER, "oldHost1234");
    String errorMessage = forwarder.applyHeaderFunctions(logger, reqHeaders);
    Assert.assertNull(errorMessage);
    Assert.assertTrue(reqHeaders.get(HOST_HEADER).equals("newHost"));
  }

  @Test
  public void testHeaderFunctionsDefaultWithHostHeader(TestContext context) {
    Rule rule = extractRule("/ruleWithHostHeader");
    Forwarder forwarder = new Forwarder(vertx, httpClient, rule, storage, loggingResourceManager,
        monitoringHandler, userProfilePath);
    MultiMap reqHeaders = new VertxHttpHeaders();
    reqHeaders.add(HOST_HEADER, DEFAULTHOST + ":" + DEFAULTPORT);
    String errorMessage = forwarder.applyHeaderFunctions(logger, reqHeaders);
    Assert.assertNull(errorMessage);
    Assert.assertTrue(reqHeaders.get(HOST_HEADER).equals("newHost"));
  }

  @Test
  public void testHeaderFunctionsWithoutHostHeader(TestContext context) {
    Rule rule = extractRule("/ruleWithoutHeader");
    Forwarder forwarder = new Forwarder(vertx, httpClient, rule, storage, loggingResourceManager,
        monitoringHandler, userProfilePath);
    MultiMap reqHeaders = new VertxHttpHeaders();
    reqHeaders.add(HOST_HEADER, "oldHost:1234");
    String errorMessage = forwarder.applyHeaderFunctions(logger, reqHeaders);
    Assert.assertNull(errorMessage);
    Assert.assertTrue(reqHeaders.get(HOST_HEADER).equals(DEFAULTHOST + ":" + DEFAULTPORT));
  }

  @Test
  public void testHeaderFunctionsDefaultWithoutHostHeader(TestContext context) {
    Rule rule = extractRule("/ruleWithoutHeader");
    Forwarder forwarder = new Forwarder(vertx, httpClient, rule, storage, loggingResourceManager,
        monitoringHandler, userProfilePath);
    MultiMap reqHeaders = new VertxHttpHeaders();
    reqHeaders.add(HOST_HEADER, DEFAULTHOST + ":" + DEFAULTPORT);
    String errorMessage = forwarder.applyHeaderFunctions(logger, reqHeaders);
    Assert.assertNull(errorMessage);
    Assert.assertTrue(reqHeaders.get(HOST_HEADER).equals(DEFAULTHOST + ":" + DEFAULTPORT));
  }


}
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
import org.swisspush.gateleen.logging.LogAppenderRepository;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.monitoring.MonitoringHandler;

import java.util.regex.Pattern;

/**
 * Tests for the Forwarder class
 */
@RunWith(VertxUnitRunner.class)
public class ForwarderTest {

    private Vertx vertx;
    private LoggingResourceManager loggingResourceManager;
    private LogAppenderRepository logAppenderRepository;
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
        logAppenderRepository = Mockito.mock(LogAppenderRepository.class);
        monitoringHandler = Mockito.mock(MonitoringHandler.class);
        httpClient = Mockito.mock(HttpClient.class);
        storage = new MockResourceStorage(ImmutableMap.of(RULES_PATH, RULES));
        logger = LoggerFactory.getLogger(ForwarderTest.class);
    }

    @Test
    public void testHeaderFunctionsGivenHostUpdatedByConfiguredRuleHostHeader() {
        Rule rule = extractRule("/ruleWithHostHeader");
        Forwarder forwarder = new Forwarder(vertx, httpClient, rule, storage, loggingResourceManager, logAppenderRepository,
                monitoringHandler, USER_PROFILE_PATH, null);
        MultiMap reqHeaders = new HeadersMultiMap();
        reqHeaders.add(HOST_HEADER, HOST_OLD);
        String errorMessage = forwarder.applyHeaderFunctions(logger, reqHeaders);
        Assert.assertNull(errorMessage);
        Assert.assertEquals(HOST_NEW, reqHeaders.get(HOST_HEADER));
    }

    @Test
    public void testHeaderFunctionsDefaultHostHeaderUpdatedByConfiguredRuleHostHeader() {
        Rule rule = extractRule("/ruleWithHostHeader");
        Forwarder forwarder = new Forwarder(vertx, httpClient, rule, storage, loggingResourceManager, logAppenderRepository,
                monitoringHandler, USER_PROFILE_PATH, null);
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
        Forwarder forwarder = new Forwarder(vertx, httpClient, rule, storage, loggingResourceManager, logAppenderRepository,
                monitoringHandler, USER_PROFILE_PATH, null);
        MultiMap reqHeaders = new HeadersMultiMap();
        reqHeaders.add(HOST_HEADER, HOST_NEW);
        String errorMessage = forwarder.applyHeaderFunctions(logger, reqHeaders);
        Assert.assertNull(errorMessage);
        Assert.assertEquals(HOST_DEFAULT, reqHeaders.get(HOST_HEADER));
    }

    @Test
    public void testHeaderFunctionsGivenHostHeaderUpdatedByDefaultHostHeaderWhenNoConfiguredRuleHostHeader() {
        Rule rule = extractRule("/ruleWithoutHeader");
        Forwarder forwarder = new Forwarder(vertx, httpClient, rule, storage, loggingResourceManager, logAppenderRepository,
                monitoringHandler, USER_PROFILE_PATH, null);
        MultiMap reqHeaders = new HeadersMultiMap();
        reqHeaders.add(HOST_HEADER, HOST_OLD);
        String errorMessage = forwarder.applyHeaderFunctions(logger, reqHeaders);
        Assert.assertNull(errorMessage);
        Assert.assertEquals(HOST_DEFAULT, reqHeaders.get(HOST_HEADER));
    }

    @Test
    public void testHeaderFunctionsGivenDefaultHostHeaderRemainsWhenNoConfiguredRuleHostHeader() {
        Rule rule = extractRule("/ruleWithoutHeader");
        Forwarder forwarder = new Forwarder(vertx, httpClient, rule, storage, loggingResourceManager, logAppenderRepository,
                monitoringHandler, USER_PROFILE_PATH, null);
        MultiMap reqHeaders = new HeadersMultiMap();
        reqHeaders.add(HOST_HEADER, HOST_DEFAULT);
        String errorMessage = forwarder.applyHeaderFunctions(logger, reqHeaders);
        Assert.assertNull(errorMessage);
        Assert.assertEquals(HOST_DEFAULT, reqHeaders.get(HOST_HEADER));
    }

    // ========================================================================
    // Tests for URL suffix behavior (GitHub issue #758)
    // These tests verify the current behavior where path suffixes are always
    // appended to the destination URL when forwarding requests.
    // ========================================================================

    /**
     * Tests that when a request has a path suffix beyond the hook registration path,
     * the suffix is appended to the destination path.
     * <p>
     * This is the current behavior that GitHub issue #758 describes:
     * - Hook registered at: /gateleen/server/push/v1/publish/my-project
     * - Destination: /v1/projects/my-project/messages:send
     * - Request: /gateleen/server/push/v1/publish/my-project/some-token
     * - Current result: /v1/projects/my-project/messages:send/some-token (suffix appended)
     */
    @Test
    public void testTargetUriConstruction_SuffixIsAppendedToDestination() {
        Pattern urlPattern = Pattern.compile("/gateleen/server/push/v1/publish/my-project");
        String destinationPath = "/v1/projects/my-project/messages:send";
        String requestUri = "/gateleen/server/push/v1/publish/my-project/some-token";

        String targetUri = Forwarder.buildTargetUri(urlPattern, requestUri, destinationPath);

        Assert.assertEquals("/v1/projects/my-project/messages:send/some-token", targetUri);
    }

    /**
     * Tests that when a request exactly matches the hook registration path (no suffix),
     * the destination path is returned as-is.
     */
    @Test
    public void testTargetUriConstruction_NoSuffixWhenRequestMatchesExactly() {
        Pattern urlPattern = Pattern.compile("/gateleen/server/push/v1/publish/my-project");
        String destinationPath = "/v1/projects/my-project/messages:send";
        String requestUri = "/gateleen/server/push/v1/publish/my-project";

        String targetUri = Forwarder.buildTargetUri(urlPattern, requestUri, destinationPath);

        Assert.assertEquals("/v1/projects/my-project/messages:send", targetUri);
    }

    /**
     * Tests suffix appending with a deeply nested path suffix.
     */
    @Test
    public void testTargetUriConstruction_DeepSuffixIsAppended() {
        Pattern urlPattern = Pattern.compile("/api/gateway");
        String destinationPath = "/backend/service";
        String requestUri = "/api/gateway/users/123/profile/settings";

        String targetUri = Forwarder.buildTargetUri(urlPattern, requestUri, destinationPath);

        Assert.assertEquals("/backend/service/users/123/profile/settings", targetUri);
    }

    /**
     * Tests that double slashes are normalized to single slashes.
     * This can happen when the destination path ends with "/" and the suffix starts with "/".
     */
    @Test
    public void testTargetUriConstruction_DoubleSlashesAreNormalized() {
        Pattern urlPattern = Pattern.compile("/api/gateway");
        String destinationPath = "/backend/service/";
        String requestUri = "/api/gateway/resource";

        String targetUri = Forwarder.buildTargetUri(urlPattern, requestUri, destinationPath);

        Assert.assertEquals("/backend/service/resource", targetUri);
    }

    /**
     * Tests suffix behavior with query parameters.
     * The entire request URI including query string is subject to the pattern replacement.
     */
    @Test
    public void testTargetUriConstruction_SuffixWithQueryParameters() {
        Pattern urlPattern = Pattern.compile("/api/gateway");
        String destinationPath = "/backend/service";
        String requestUri = "/api/gateway/resource?param=value&other=123";

        String targetUri = Forwarder.buildTargetUri(urlPattern, requestUri, destinationPath);

        Assert.assertEquals("/backend/service/resource?param=value&other=123", targetUri);
    }

    /**
     * Tests the FCM (Firebase Cloud Messaging) scenario from GitHub issue #758.
     * This demonstrates the problem: FCM expects a fixed endpoint without any path suffix,
     * but the current behavior appends the suffix. Issue #758 proposes adding a "stripPath" option.
     */
    @Test
    public void testTargetUriConstruction_FCMScenario_SuffixIsAppended() {
        Pattern urlPattern = Pattern.compile("/gateleen/server/push/v1/publish/my-project");
        String destinationPath = "/v1/projects/my-project/messages:send";
        String requestUri = "/gateleen/server/push/v1/publish/my-project/device-token-abc123";

        String targetUri = Forwarder.buildTargetUri(urlPattern, requestUri, destinationPath);

        Assert.assertEquals("/v1/projects/my-project/messages:send/device-token-abc123", targetUri);
    }

    // ========================================================================
    // Tests for fullUrl=true behavior (GitHub issue #758 fix)
    // These tests verify that when fullUrl=true, the path suffix is NOT appended.
    // ========================================================================

    @Test
    public void testTargetUriConstruction_FullUrlTrue_SuffixIsNotAppended() {
        Pattern urlPattern = Pattern.compile("/gateleen/server/push/v1/publish/my-project");
        String destinationPath = "/v1/projects/my-project/messages:send";
        String requestUri = "/gateleen/server/push/v1/publish/my-project/some-token";

        String targetUri = Forwarder.buildTargetUri(urlPattern, requestUri, destinationPath, true);

        Assert.assertEquals("/v1/projects/my-project/messages:send", targetUri);
    }

    @Test
    public void testTargetUriConstruction_FullUrlTrue_FCMScenario_ExactDestination() {
        Pattern urlPattern = Pattern.compile("/gateleen/server/push/v1/publish/my-project");
        String destinationPath = "/v1/projects/my-project/messages:send";
        String requestUri = "/gateleen/server/push/v1/publish/my-project/device-token-abc123";

        String targetUri = Forwarder.buildTargetUri(urlPattern, requestUri, destinationPath, true);

        Assert.assertEquals("/v1/projects/my-project/messages:send", targetUri);
    }

    @Test
    public void testTargetUriConstruction_FullUrlTrue_DeepSuffixIgnored() {
        Pattern urlPattern = Pattern.compile("/api/gateway");
        String destinationPath = "/backend/service";
        String requestUri = "/api/gateway/users/123/profile/settings";

        String targetUri = Forwarder.buildTargetUri(urlPattern, requestUri, destinationPath, true);

        Assert.assertEquals("/backend/service", targetUri);
    }

    @Test
    public void testTargetUriConstruction_FullUrlTrue_DoubleSlashesNormalized() {
        Pattern urlPattern = Pattern.compile("/api/gateway");
        String destinationPath = "/backend/service//endpoint";
        String requestUri = "/api/gateway/ignored";

        String targetUri = Forwarder.buildTargetUri(urlPattern, requestUri, destinationPath, true);

        Assert.assertEquals("/backend/service/endpoint", targetUri);
    }

    @Test
    public void testTargetUriConstruction_FullUrlFalse_BehavesLikeDefault() {
        Pattern urlPattern = Pattern.compile("/api/gateway");
        String destinationPath = "/backend/service";
        String requestUri = "/api/gateway/resource";

        String withExplicitFalse = Forwarder.buildTargetUri(urlPattern, requestUri, destinationPath, false);
        String withDefault = Forwarder.buildTargetUri(urlPattern, requestUri, destinationPath);

        Assert.assertEquals(withDefault, withExplicitFalse);
        Assert.assertEquals("/backend/service/resource", withExplicitFalse);
    }

}
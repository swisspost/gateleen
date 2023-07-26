package org.swisspush.gateleen.routing;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.auth.oauth2.OAuth2FlowType;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.routing.auth.OAuthConfiguration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tests for the {@link RouterConfigurationParser}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class RouterConfigurationParserTest {

    private final String CONFIG_WILDCARD_RESOURCE = ResourcesUtils.loadResource(
            "testresource_wildcard_routing_configuration", true);
    private final String CONFIG_VALID_INVALID_RESOURCE = ResourcesUtils.loadResource(
            "testresource_valid_invalid_routing_configuration", true);

    @Test
    public void parseValidNoAuthConfigs(TestContext context) {
        Optional<RouterConfiguration> routerConfigurationOpt = RouterConfigurationParser.parse(
                Buffer.buffer("{\"request.hops.limit\": 10}"), new HashMap<>());
        context.assertTrue(routerConfigurationOpt.isPresent());

        RouterConfiguration config = routerConfigurationOpt.get();
        context.assertEquals(10, config.requestHopsLimit());
        context.assertTrue(config.oAuthConfigurations().isEmpty());
    }

    @Test
    public void parseMissingWildcardProperty(TestContext context) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("zzz", "foobar");
        Optional<RouterConfiguration> routerConfigurationOpt = RouterConfigurationParser.parse(Buffer.buffer(CONFIG_WILDCARD_RESOURCE), properties);
        context.assertFalse(routerConfigurationOpt.isPresent());
    }

    @Test
    public void parseWildcardValid(TestContext context) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("client.id", "foobar");
        Optional<RouterConfiguration> routerConfigurationOpt = RouterConfigurationParser.parse(Buffer.buffer(CONFIG_WILDCARD_RESOURCE), properties);
        context.assertTrue(routerConfigurationOpt.isPresent());

        RouterConfiguration config = routerConfigurationOpt.get();
        context.assertEquals(10, config.requestHopsLimit());
        context.assertEquals(1, config.oAuthConfigurations().size());

        Optional<OAuthConfiguration> authConfiguration = config.oAuthConfiguration("config-1-valid");
        context.assertTrue(authConfiguration.isPresent());

        OAuthConfiguration configuration = authConfiguration.get();
        context.assertEquals(OAuth2FlowType.CLIENT, configuration.flowType());
        context.assertEquals("foobar", configuration.clientId()); // value from properties map
        context.assertEquals("20abaf4381beeb25c1cf811d339b3a6a", configuration.clientSecret());
        context.assertEquals("https://api.swisspost.ch", configuration.site());
        context.assertEquals("/OAuth/token", configuration.tokenPath());
        context.assertEquals("/OAuth/authorization", configuration.authPath());
        context.assertEquals(List.of("APIM_SANDBOX_RESOURCE_SERVER_READ"), configuration.scopes());
        context.assertNull(configuration.supportedGrantTypes());
    }

    @Test
    public void parseValidAndInvalid(TestContext context) {
        Optional<RouterConfiguration> routerConfigurationOpt = RouterConfigurationParser.parse(
                Buffer.buffer(CONFIG_VALID_INVALID_RESOURCE), new HashMap<>());
        context.assertTrue(routerConfigurationOpt.isPresent());

        RouterConfiguration config = routerConfigurationOpt.get();
        context.assertEquals(10, config.requestHopsLimit());
        context.assertEquals(2, config.oAuthConfigurations().size());

        Optional<OAuthConfiguration> authConfiguration1 = config.oAuthConfiguration("config-1-valid");
        context.assertTrue(authConfiguration1.isPresent());

        OAuthConfiguration configuration1 = authConfiguration1.get();
        context.assertEquals(OAuth2FlowType.CLIENT, configuration1.flowType());
        context.assertEquals("56zhgf34t6z7", configuration1.clientId()); // value from properties map
        context.assertEquals("20abaf4381beeb25c1cf811d339b3a6a", configuration1.clientSecret());
        context.assertEquals("https://api.swisspost.ch", configuration1.site());
        context.assertEquals("/OAuth/token", configuration1.tokenPath());
        context.assertEquals("/OAuth/authorization", configuration1.authPath());
        context.assertEquals(List.of("APIM_SANDBOX_RESOURCE_SERVER_READ"), configuration1.scopes());
        context.assertNull(configuration1.supportedGrantTypes());

        // config-2-invalid was not valid, should not exist
        context.assertFalse(config.oAuthConfiguration("config-2-invalid").isPresent());

        Optional<OAuthConfiguration> authConfiguration3 = config.oAuthConfiguration("config-3-valid");
        context.assertTrue(authConfiguration3.isPresent());

        OAuthConfiguration configuration3 = authConfiguration3.get();
        context.assertEquals(OAuth2FlowType.PASSWORD, configuration3.flowType());
        context.assertEquals("h7b56453rth7", configuration3.clientId()); // value from properties map
        context.assertEquals("76uuh5g4frdwe3a6a", configuration3.clientSecret());
        context.assertEquals("https://api.swisspost.ch", configuration3.site());
        context.assertEquals("/OAuth/token", configuration3.tokenPath());
        context.assertEquals("/OAuth/authorization", configuration3.authPath());
        context.assertNull(configuration3.scopes());
        context.assertEquals(List.of("client_credentials"), configuration3.supportedGrantTypes());
    }
}

package org.swisspush.gateleen.routing;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.oauth2.OAuth2FlowType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.StringUtils;
import org.swisspush.gateleen.routing.auth.OAuthConfiguration;
import org.swisspush.gateleen.routing.auth.OAuthId;

import javax.annotation.Nullable;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;

public class RouterConfigurationParser {

    private static final Logger log = LoggerFactory.getLogger(RouterConfigurationParser.class);

    private static final String REQUEST_HOPS_LIMIT_PROPERTY = "request.hops.limit";
    private static final String AUTH_CONFIGS_PROPERTY = "authConfigs";
    private static final String FLOWTYPE_PROPERTY = "flowType";
    private static final String CLIENT_ID_PROPERTY = "clientId";
    private static final String CLIENT_SECRET_PROPERTY = "clientSecret";
    private static final String SITE_PROPERTY = "site";
    private static final String TOKENPATH_PROPERTY = "tokenPath";
    private static final String AUTHPATH_PROPERTY = "authPath";
    private static final String SCOPES_PROPERTY = "scopes";
    private static final String SUPPORTED_GRANTTYPES_PROPERTY = "supportedGrantTypes";

    /**
     * Parses the provided router configuration resource and returns a {@link RouterConfiguration}.
     *
     * @param configurationResourceBuffer the resource to parse
     * @return a {@link RouterConfiguration}
     */
    public static Optional<RouterConfiguration> parse(Buffer configurationResourceBuffer,
                                                      Map<String, Object> properties) {
        String replacedConfig;
        JsonObject config;

        try {
            replacedConfig = StringUtils.replaceWildcardConfigs(configurationResourceBuffer.toString(UTF_8), properties);
            config = new JsonObject(Buffer.buffer(replacedConfig));
        } catch (Exception e) {
            log.warn("Could not replace wildcards with environment properties for the router configuration " +
                            "due to following reason: {}", e.getMessage());
            return Optional.empty();
        }

        Integer requestHopsLimit = config.getInteger(REQUEST_HOPS_LIMIT_PROPERTY);

        JsonObject authConfigs = config.getJsonObject(AUTH_CONFIGS_PROPERTY);
        if (authConfigs == null) {
            return Optional.of(new RouterConfiguration(requestHopsLimit, Collections.emptyMap()));
        }

        Map<OAuthId, OAuthConfiguration> oAuthConfigurationsMap = new HashMap<>();

        for (String authConfigId : authConfigs.fieldNames()) {
            JsonObject authConfig = authConfigs.getJsonObject(authConfigId);

            String flowTypeStr = authConfig.getString(FLOWTYPE_PROPERTY);
            OAuth2FlowType flowType = flowTypeFromStr(flowTypeStr);

            if(flowType == null) {
                log.warn("No valid OAuth2FlowType configured for auth configuration '{}'. " +
                        "Unable to use this configuration", authConfigId);
                continue;
            }

            String clientId = authConfig.getString(CLIENT_ID_PROPERTY);
            String clientSecret = authConfig.getString(CLIENT_SECRET_PROPERTY);
            String site = authConfig.getString(SITE_PROPERTY);
            String tokenPath = authConfig.getString(TOKENPATH_PROPERTY);
            String authPath = authConfig.getString(AUTHPATH_PROPERTY);

            JsonArray scopesArray = authConfig.getJsonArray(SCOPES_PROPERTY);
            List<String> scopeList = null;
            if(scopesArray != null) {
                scopeList = new ArrayList<>();
                for (Object scope : scopesArray) {
                    scopeList.add((String) scope);
                }
            }

            JsonArray supportedGrantTypesArray = authConfig.getJsonArray(SUPPORTED_GRANTTYPES_PROPERTY);
            List<String> supportedGrantTypesList = null;
            if(supportedGrantTypesArray != null) {
                supportedGrantTypesList = new ArrayList<>();
                for (Object scope : supportedGrantTypesArray) {
                    supportedGrantTypesList.add((String) scope);
                }
            }

            OAuthConfiguration oAuthConfiguration = new OAuthConfiguration(flowType, clientId, clientSecret,
                    site, tokenPath, authPath, scopeList, supportedGrantTypesList);
            oAuthConfigurationsMap.put(OAuthId.of(authConfigId), oAuthConfiguration);
        }

        return Optional.of(new RouterConfiguration(requestHopsLimit, oAuthConfigurationsMap));
    }

    @Nullable
    private static OAuth2FlowType flowTypeFromStr(String flowTypeStr) {
        try {
            return OAuth2FlowType.valueOf(flowTypeStr);
        } catch (IllegalArgumentException ex) {
            log.warn("No valid OAuth2FlowType found for '{}'", flowTypeStr);
            return null;
        }
    }
}

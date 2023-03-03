package org.swisspush.gateleen.routing;

import org.swisspush.gateleen.routing.auth.OAuthConfiguration;
import org.swisspush.gateleen.routing.auth.OAuthId;

import java.util.Map;
import java.util.Optional;

/**
 * Container holding configuration values for the {@link Router}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class RouterConfiguration {

    private final Integer requestHopsLimit;
    private final Map<OAuthId, OAuthConfiguration> oAuthConfigurations;

    public RouterConfiguration(Integer requestHopsLimit, Map<OAuthId, OAuthConfiguration> oAuthConfigurations) {
        this.requestHopsLimit = requestHopsLimit;
        this.oAuthConfigurations = oAuthConfigurations;
    }

    public Integer requestHopsLimit() {
        return requestHopsLimit;
    }

    public Map<OAuthId, OAuthConfiguration> oAuthConfigurations() {
        return oAuthConfigurations;
    }

    public Optional<OAuthConfiguration> oAuthConfiguration(String id) {
        return Optional.ofNullable(oAuthConfigurations.get(OAuthId.of(id)));
    }
}

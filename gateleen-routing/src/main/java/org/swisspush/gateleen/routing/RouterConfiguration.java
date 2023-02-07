package org.swisspush.gateleen.routing;

import org.swisspush.gateleen.routing.auth.OAuthConfiguration;

import java.util.Map;
import java.util.Optional;

/**
 * Container holding configuration values for the {@link Router}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class RouterConfiguration {

    private final Integer requestHopsLimit;
    private final Map<String, OAuthConfiguration> oAuthConfigurations;

    public RouterConfiguration(Integer requestHopsLimit, Map<String, OAuthConfiguration> oAuthConfigurations) {
        this.requestHopsLimit = requestHopsLimit;
        this.oAuthConfigurations = oAuthConfigurations;
    }

    public Integer requestHopsLimit() {
        return requestHopsLimit;
    }

    public Map<String, OAuthConfiguration> oAuthConfigurations() {
        return oAuthConfigurations;
    }

    public Optional<OAuthConfiguration> oAuthConfiguration(String id) {
        return Optional.ofNullable(oAuthConfigurations.get(id));
    }
}

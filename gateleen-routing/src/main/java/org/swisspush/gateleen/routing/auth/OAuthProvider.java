package org.swisspush.gateleen.routing.auth;

import io.vertx.core.Future;
import org.swisspush.gateleen.routing.RouterConfiguration;

import java.util.Optional;

public interface OAuthProvider {

    void updateRouterConfiguration(Optional<RouterConfiguration> routerConfiguration);

    Future<String> requestAccessToken(OAuthId oAuthId);
}

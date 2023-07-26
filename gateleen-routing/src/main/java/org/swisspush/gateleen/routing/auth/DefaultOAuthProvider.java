package org.swisspush.gateleen.routing.auth;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.oauth2.OAuth2Auth;
import io.vertx.ext.auth.oauth2.OAuth2Options;
import io.vertx.ext.auth.oauth2.Oauth2Credentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.routing.RouterConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class DefaultOAuthProvider implements OAuthProvider {

    private final Vertx vertx;
    private static final Logger log = LoggerFactory.getLogger(DefaultOAuthProvider.class);

    private Map<OAuthId, OAuthDelegate> oAuthConfigurationMap = new HashMap<>();
    private Map<OAuthId, User> userMap = new HashMap<>();

    public DefaultOAuthProvider(Vertx vertx) {
        this.vertx = vertx;
    }

    public void updateRouterConfiguration(Optional<RouterConfiguration> routerConfigurationOptional) {
        oAuthConfigurationMap.clear();
        userMap.clear();
        routerConfigurationOptional.ifPresent(routerConfiguration -> {
            for (Map.Entry<OAuthId, OAuthConfiguration> entry : routerConfiguration.oAuthConfigurations().entrySet()) {
                OAuthDelegate oAuthDelegate = buildOAuthDelegate(entry.getValue());
                oAuthConfigurationMap.put(entry.getKey(), oAuthDelegate);
            }
        });
    }

    public Future<String> requestAccessToken(OAuthId oAuthId) {
        OAuthDelegate delegate = oAuthConfigurationMap.get(oAuthId);
        if (delegate == null) {
            return Future.failedFuture("No OAuth configuration found for id " + oAuthId.oAuthId());
        }

        String cachedToken = cachedToken(oAuthId);
        if (cachedToken != null) {
            return Future.succeededFuture(cachedToken);
        }

        log.info("About to request new access token for oAuthId '{}'", oAuthId.oAuthId());
        return delegate.authenticate().compose(user -> {
            userMap.put(oAuthId, user);
            String token = cachedToken(oAuthId);
            if (token == null) {
                return Future.failedFuture("No access token received from user from oAuthId '" + oAuthId.oAuthId() + "' object");
            }
            return Future.succeededFuture(token);
        });
    }

    private String cachedToken(OAuthId oAuthId) {
        User user = userMap.get(oAuthId);
        if (user != null) {
            if (log.isTraceEnabled()) {
                log.trace("User attributes for oAuthId '{}': {}", oAuthId.oAuthId(), user.attributes().encode());
            }
            if (user.expired()) {
                log.debug("User for oAuthId '{}' is expired", oAuthId.oAuthId());
                userMap.remove(oAuthId);
            } else {
                return user.principal().getString("access_token");
            }
        }
        return null;
    }

    private OAuthDelegate buildOAuthDelegate(OAuthConfiguration oAuthConfiguration) {
        OAuth2Options credentials = new OAuth2Options()
                .setFlow(oAuthConfiguration.flowType())
                .setClientId(oAuthConfiguration.clientId())
                .setClientSecret(oAuthConfiguration.clientSecret())
                .setSupportedGrantTypes(oAuthConfiguration.supportedGrantTypes())
                .setAuthorizationPath(oAuthConfiguration.authPath())
                .setTokenPath(oAuthConfiguration.tokenPath())
                .setSite(oAuthConfiguration.site());

        OAuth2Auth oauth2 = OAuth2Auth.create(vertx, credentials);
        Oauth2Credentials oauth2Credentials = new Oauth2Credentials();

        if (oAuthConfiguration.scopes() != null) {
            for (String scope : Objects.requireNonNull(oAuthConfiguration.scopes())) {
                oauth2Credentials.addScope(scope);
            }
        }
        return new OAuthDelegate(oauth2, oauth2Credentials);
    }

    private static class OAuthDelegate {
        private final OAuth2Auth oAuth2Auth;
        private final Oauth2Credentials oauth2Credentials;

        public OAuthDelegate(OAuth2Auth oAuth2Auth, Oauth2Credentials oauth2Credentials) {
            this.oAuth2Auth = oAuth2Auth;
            this.oauth2Credentials = oauth2Credentials;
        }

        public Future<User> authenticate() {
            return oAuth2Auth.authenticate(oauth2Credentials);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            OAuthDelegate that = (OAuthDelegate) o;

            if (!oAuth2Auth.equals(that.oAuth2Auth)) return false;
            return oauth2Credentials.equals(that.oauth2Credentials);
        }

        @Override
        public int hashCode() {
            int result = oAuth2Auth.hashCode();
            result = 31 * result + oauth2Credentials.hashCode();
            return result;
        }
    }
}

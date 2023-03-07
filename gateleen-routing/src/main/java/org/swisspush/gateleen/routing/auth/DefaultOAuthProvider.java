package org.swisspush.gateleen.routing.auth;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.oauth2.OAuth2Auth;
import io.vertx.ext.auth.oauth2.OAuth2Options;
import io.vertx.ext.auth.oauth2.Oauth2Credentials;
import org.swisspush.gateleen.routing.RouterConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class DefaultOAuthProvider implements OAuthProvider {

    private final Vertx vertx;
    
    private Map<OAuthId, OAuthDelegate> oAuthConfigurationMap = new HashMap<>();

    public DefaultOAuthProvider(Vertx vertx) {
        this.vertx = vertx;
    }

    public void updateRouterConfiguration(Optional<RouterConfiguration> routerConfigurationOptional) {
        oAuthConfigurationMap.clear();
        routerConfigurationOptional.ifPresent(routerConfiguration -> {
            for (Map.Entry<OAuthId, OAuthConfiguration> entry : routerConfiguration.oAuthConfigurations().entrySet()) {
                OAuthDelegate oAuthDelegate = buildOAuthDelegate(entry.getValue());
                oAuthConfigurationMap.put(entry.getKey(), oAuthDelegate);
            }
        });
    }

    public Future<String> requestAccessToken(OAuthId oAuthId) {
        OAuthDelegate delegate = oAuthConfigurationMap.get(oAuthId);
        if(delegate == null) {
            return Future.failedFuture("No OAuth configuration found for id " + oAuthId.oAuthId());
        }

        return delegate.authenticate().compose(user -> {
            String token = user.principal().getString("access_token");
            if (token == null) {
                return Future.failedFuture("No access_token received from user object");
            }
            return Future.succeededFuture(token);
        });
    }

    private OAuthDelegate buildOAuthDelegate(OAuthConfiguration oAuthConfiguration){
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
        
        if(oAuthConfiguration.scopes() != null) {
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

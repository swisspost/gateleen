package org.swisspush.gateleen.routing.auth;

import io.vertx.core.Future;
import org.swisspush.gateleen.routing.Rule;

public class OAuthStrategy implements AuthStrategy {

    private final OAuthProvider oAuthProvider;

    public OAuthStrategy(OAuthProvider oAuthProvider) {
        this.oAuthProvider = oAuthProvider;
    }

    @Override
    public Future<AuthHeader> authenticate(Rule rule) {
        if(rule.getOAuthId() == null) {
            return Future.failedFuture("Unable to authenticate request because no oAuthId provided." +
                    " This should not happen!");
        }
        return oAuthProvider.requestAccessToken(rule.getOAuthId()).compose(
                accessToken -> Future.succeededFuture(new AuthHeader("Bearer " + accessToken)));
    }
}

package org.swisspush.gateleen.routing.auth;

import io.vertx.core.Future;
import org.swisspush.gateleen.core.util.StringUtils;
import org.swisspush.gateleen.routing.Rule;

import java.util.Base64;

/**
 * An {@link AuthStrategy} that implements a basic authentication with username and password provided
 * in the {@link Rule}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class BasicAuthStrategy implements AuthStrategy {

    @Override
    public Future<AuthHeader> authenticate(Rule rule) {
        if (StringUtils.isNotEmpty(rule.getBasicAuthUsername())) {
            String password = StringUtils.getStringOrDefault(rule.getBasicAuthPassword(), null);
            String base64UsernamePassword = Base64.getEncoder().encodeToString((rule.getBasicAuthUsername().trim()
                    + ":" + password).getBytes());
            return Future.succeededFuture(new AuthHeader("Basic " + base64UsernamePassword));
        } else {
            return Future.failedFuture("Unable to authenticate request because no basic auth " +
                    "credentials provided. This should not happen!");
        }
    }
}

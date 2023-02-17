package org.swisspush.gateleen.routing.auth;

import io.vertx.core.Future;
import org.swisspush.gateleen.routing.Rule;

public interface AuthStrategy {
    Future<AuthHeader> authenticate(Rule rule);
}

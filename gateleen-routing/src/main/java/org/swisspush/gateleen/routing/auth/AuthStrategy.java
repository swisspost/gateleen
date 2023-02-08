package org.swisspush.gateleen.routing.auth;

import io.vertx.core.http.HttpClientRequest;
import org.swisspush.gateleen.routing.Rule;

public interface AuthStrategy {
    void authenticate(HttpClientRequest clientRequest, Rule rule);
}

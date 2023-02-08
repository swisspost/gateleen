package org.swisspush.gateleen.routing.auth;

import io.vertx.core.http.HttpClientRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.StringUtils;
import org.swisspush.gateleen.routing.Rule;

import java.util.Base64;

/**
 * An {@link AuthStrategy} that implements a basic authentication whith username and password provided
 * in the {@link Rule}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class BasicAuthStrategy implements AuthStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(BasicAuthStrategy.class);

    @Override
    public void authenticate(HttpClientRequest clientRequest, Rule rule) {
        if (StringUtils.isNotEmpty(rule.getBasicAuthUsername())) {
            String password = StringUtils.getStringOrDefault(rule.getBasicAuthPassword(), null);
            String base64UsernamePassword = Base64.getEncoder().encodeToString((rule.getBasicAuthUsername().trim() + ":" + password).getBytes());
            clientRequest.headers().set("Authorization", "Basic " + base64UsernamePassword);
        } else {
            LOG.warn("Unable to authenticate request because no basic auth credentials provided. This should not happen!");
        }
    }
}

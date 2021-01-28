package org.swisspush.gateleen.delegate;

import io.vertx.core.json.JsonObject;
import org.swisspush.gateleen.core.json.transform.JoltSpec;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Container class holding a request as {@link io.vertx.core.json.JsonObject} and an
 * optional {@link org.swisspush.gateleen.core.json.transform.JoltSpec} for payload transformation.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class DelegateRequest {
    private final JsonObject request;
    private final JoltSpec joltSpec;
    private final Pattern propagateSourceHeadersPattern;

    public DelegateRequest(JsonObject request, JoltSpec joltSpec, Pattern propagateSourceHeadersPattern) {
        this.request = request;
        this.joltSpec = joltSpec;
        this.propagateSourceHeadersPattern = propagateSourceHeadersPattern;
    }

    public JsonObject getRequest() {
        return request;
    }

    public JoltSpec getJoltSpec() {
        return joltSpec;
    }

    public Optional<Pattern> getPropagateSourceHeadersPattern() {
        return Optional.ofNullable(propagateSourceHeadersPattern);
    }
}

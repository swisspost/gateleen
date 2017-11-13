package org.swisspush.gateleen.delegate;

import io.vertx.core.json.JsonObject;
import org.swisspush.gateleen.core.json.transform.JoltSpec;

/**
 * Container class holding a request as {@link io.vertx.core.json.JsonObject} and an
 * optional {@link org.swisspush.gateleen.core.json.transform.JoltSpec} for payload transformation.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class DelegateRequest {
    private final JsonObject request;
    private final JoltSpec joltSpec;

    public DelegateRequest(JsonObject request, JoltSpec joltSpec) {
        this.request = request;
        this.joltSpec = joltSpec;
    }

    public JsonObject getRequest() {
        return request;
    }

    public JoltSpec getJoltSpec() {
        return joltSpec;
    }
}

package org.swisspush.gateleen.delegate;

import io.vertx.core.json.JsonObject;
import org.swisspush.gateleen.core.http.HeaderFunction;
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
    private final HeaderFunction headerFunction;
    private final boolean copy;

    public DelegateRequest(JsonObject request, JoltSpec joltSpec, HeaderFunction headerFunction, boolean copy) {
        this.request = request;
        this.joltSpec = joltSpec;
        this.headerFunction = headerFunction;
        this.copy = copy;
    }

    public JsonObject getRequest() {
        return request;
    }

    public JoltSpec getJoltSpec() {
        return joltSpec;
    }

    public HeaderFunction getHeaderFunction() {
        return headerFunction;
    }

    public boolean isCopy() {
        return copy;
    }
}

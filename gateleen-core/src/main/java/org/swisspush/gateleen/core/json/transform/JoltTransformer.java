package org.swisspush.gateleen.core.json.transform;

import com.bazaarvoice.jolt.Chainr;
import com.bazaarvoice.jolt.JsonUtils;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;

/**
 * Performs JSON-to-JSON transformations using the Jolt library
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class JoltTransformer {

    /**
     * Transforms the provided input using the {@link JoltSpec} specification.
     *
     * @param jsonInput the string json input to transform
     * @param spec the {@link JoltSpec} defining how to transform the input
     * @return a {@link Future} holding the transformed JSON or an error
     */
    public static Future<JsonObject> transform(String jsonInput, JoltSpec spec) {
        Promise<JsonObject> promise = Promise.promise();
        try {
            Chainr chainr = spec.getChainr();
            Object inputJSON = JsonUtils.jsonToObject(jsonInput);
            Object transformedOutput = chainr.transform(inputJSON);
            promise.complete(new JsonObject(JsonUtils.toJsonString(transformedOutput)));
        } catch (Exception ex){
            promise.fail(ex);
        }
        return promise.future();
    }
}

package org.swisspush.gateleen.core.json.transform;

import com.bazaarvoice.jolt.Chainr;
import com.bazaarvoice.jolt.JsonUtils;
import io.vertx.core.Future;

import java.util.List;

/**
 * Builds a {@link JoltSpec} object containing a valid Jolt transform specification.
 * This {@link JoltSpec} object can be reused for all transformations using the same specification.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class JoltSpecBuilder {

    /**
     * Builds a {@link JoltSpec} object based on the provided string representation of the json spec.
     *
     * If an error occurs during the creation of the spec, {@link Future#fail(Throwable)} is called containing
     * the error as a {@link Throwable}.
     *
     * @param jsonSpec the string representation of the spec
     * @return returns a {@link Future} holding the valid {@link JoltSpec} object or an error.
     */
    public static Future<JoltSpec> buildSpec(String jsonSpec){
        Future<JoltSpec> future = Future.future();
        try {
            List<Object> specs = JsonUtils.jsonToList(jsonSpec);
            Chainr chainr = Chainr.fromSpec(specs);
            future.complete(new JoltSpec(chainr));
        } catch (Exception ex){
            future.fail(ex);
        }
        return future;
    }
}

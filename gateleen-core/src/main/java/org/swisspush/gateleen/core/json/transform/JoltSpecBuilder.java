package org.swisspush.gateleen.core.json.transform;

import com.bazaarvoice.jolt.Chainr;
import com.bazaarvoice.jolt.JsonUtils;

import java.util.List;

/**
 * Builds a {@link JoltSpec} object containing a valid Jolt transform specification.
 * This {@link JoltSpec} object can be reused for all transformations using the same specification.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class JoltSpecBuilder {

    /**
     * See {@link JoltSpecBuilder#buildSpec(String, boolean)} with {@code false} for param withMetadata
     */
    public static JoltSpec buildSpec(String jsonSpec) throws JoltSpecException {
        return buildSpec(jsonSpec, false);
    }

    /**
     * Builds a {@link JoltSpec} object based on the provided string representation of the json spec.
     *
     * If an error occurs during the creation of the spec, a {@link JoltSpecException} is thrown.
     *
     * @param jsonSpec the string representation of the spec
     * @param withMetadata does the json input to use the spec against has meta data
     * @return returns a {@link JoltSpec}
     * @throws JoltSpecException when an error occurred during spec parsing
     */
    public static JoltSpec buildSpec(String jsonSpec, boolean withMetadata) throws JoltSpecException {
        try {
            List<Object> specs = JsonUtils.jsonToList(jsonSpec);
            Chainr chainr = Chainr.fromSpec(specs);
            return new JoltSpec(chainr, withMetadata);
        } catch (Exception ex){
            throw new JoltSpecException(ex);
        }
    }
}

package org.swisspush.gateleen.packing.validation;

import io.vertx.core.buffer.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.validation.ValidationResult;
import org.swisspush.gateleen.validation.Validator;

public class PackingValidatorImpl implements PackingValidator {

    private final String schema = ResourcesUtils.loadResource("gateleen_packing_schema", true);
    private final Logger log = LoggerFactory.getLogger(PackingValidatorImpl.class);

    @Override
    public ValidationResult validatePackingPayload(Buffer data) {
        return Validator.validateStatic(data, schema, log);
    }
}

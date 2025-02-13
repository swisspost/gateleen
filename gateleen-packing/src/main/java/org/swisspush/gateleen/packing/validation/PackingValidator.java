package org.swisspush.gateleen.packing.validation;

import io.vertx.core.buffer.Buffer;
import org.swisspush.gateleen.core.validation.ValidationResult;

public interface PackingValidator {

    ValidationResult validatePackingPayload(Buffer data);
}

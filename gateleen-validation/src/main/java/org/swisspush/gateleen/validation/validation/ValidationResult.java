package org.swisspush.gateleen.validation.validation;

import io.vertx.core.json.JsonArray;

/**
 * Class ValidationResult represents the result of a validation using a schema.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class ValidationResult {

    private ValidationStatus status;
    private String message;
    private JsonArray validationDetails;

    public ValidationResult(ValidationStatus status, String message){
        this(status, message, null);
    }

    public ValidationResult(ValidationStatus status, String message, JsonArray validationDetails){
        this.status = status;
        this.message = message;
        this.validationDetails = validationDetails;
    }

    public ValidationResult(ValidationStatus success){
        this(success, null);
    }

    public String getMessage() {
        return message;
    }

    public JsonArray getValidationDetails() { return validationDetails; }

    public ValidationStatus getValidationStatus() { return status; }

    public boolean isSuccess() {
        return ValidationStatus.VALIDATED_POSITIV.equals(status);
    }
}

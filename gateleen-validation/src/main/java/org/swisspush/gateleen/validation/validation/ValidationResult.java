package org.swisspush.gateleen.validation.validation;

/**
 * Class ValidationResult represents the result of a validation using a schema.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class ValidationResult {

    private ValidationStatus status;
    private String message;

    public ValidationResult(ValidationStatus status, String message){
        this.status = status;
        this.message = message;
    }

    public ValidationResult(ValidationStatus success){
        this(success, null);
    }

    public String getMessage() {
        return message;
    }

    public ValidationStatus getValidationStatus() { return status; }

    public boolean isSuccess() {
        return ValidationStatus.VALIDATED_POSITIV.equals(status);
    }
}

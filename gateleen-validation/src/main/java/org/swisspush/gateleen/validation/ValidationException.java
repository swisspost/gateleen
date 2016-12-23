package org.swisspush.gateleen.validation;

import io.vertx.core.json.JsonArray;
import org.swisspush.gateleen.core.validation.ValidationResult;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class ValidationException extends Exception {

    private JsonArray validationDetails;

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(Throwable cause) {super(cause); }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }

    public ValidationException(ValidationResult validationResult) {
        super(validationResult.getMessage());
        this.validationDetails = validationResult.getValidationDetails();
    }

    public JsonArray getValidationDetails() {
        return validationDetails;
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder("ValidationException: ").append(getMessage());
        if(validationDetails != null){
            stringBuilder.append(" Details: ").append(validationDetails.toString());
        }
        return stringBuilder.toString();
    }
}

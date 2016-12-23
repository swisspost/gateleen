package org.swisspush.gateleen.logging;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.validation.ValidationResult;
import org.swisspush.gateleen.core.validation.ValidationStatus;
import org.swisspush.gateleen.validation.Validator;

/**
 * Tests for the logging resource schema
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class LoggingSchemaValidationTest {

    private final String LOGGING_SCHEMA = ResourcesUtils.loadResource("gateleen_logging_schema_logging", true);
    private final String INVALID_TRANSMISSION_LOGGING_RESOURCE = ResourcesUtils.loadResource("testresource_invalid_transmission_logging_resource.txt", true);
    private final String VALID_LOGGING_RESOURCE = ResourcesUtils.loadResource("testresource_valid_logging_resource", true);

    private Logger logger = LoggerFactory.getLogger(LoggingSchemaValidationTest.class);

    @Test
    public void testValidResource(TestContext context){
        ValidationResult validationResult = validate(VALID_LOGGING_RESOURCE);
        context.assertNotNull(validationResult);
        context.assertEquals(ValidationStatus.VALIDATED_POSITIV, validationResult.getValidationStatus());
    }

    @Test
    public void testInvalidTransmission(TestContext context){
        ValidationResult validationResult = validate(INVALID_TRANSMISSION_LOGGING_RESOURCE);
        context.assertNotNull(validationResult);
        context.assertEquals(ValidationStatus.VALIDATED_NEGATIV, validationResult.getValidationStatus());
        context.assertEquals("Validation failed", validationResult.getMessage());
        context.assertEquals("instance does not match any enum value", extractErrorMessage(validationResult));
        context.assertEquals("invalid_transmission", extractErrorValue(validationResult));
    }

    private String extractErrorMessage(ValidationResult validationResult){
        return validationResult.getValidationDetails().getJsonObject(0).getString("message");
    }

    private String extractErrorValue(ValidationResult validationResult){
        return validationResult.getValidationDetails().getJsonObject(0).getString("value");
    }

    private ValidationResult validate(String loggingResource){
        return Validator.validateStatic(Buffer.buffer(loggingResource), LOGGING_SCHEMA, logger);
    }
}

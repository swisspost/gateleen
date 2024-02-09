package org.swisspush.gateleen.queue.queuing.splitter;

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

@RunWith(VertxUnitRunner.class)
public class QueueConfigurationSchemaValidationTest {

    private final String CONFIG_SCHEMA = ResourcesUtils.loadResource("gateleen_queue_splitter_configuration_schema", true);

    private final Logger logger = LoggerFactory.getLogger(QueueConfigurationSchemaValidationTest.class);

    private final String CONFIG_RESOURCE_VALID = ResourcesUtils.loadResource("testresource_queuesplitter_configuration_valid_1", true);

    private final String CONFIG_RESOURCE_MISSING_POSTFIX = ResourcesUtils.loadResource("testresource_queuesplitter_configuration_missing_postfix", true);
    private final String CONFIG_RESOURCE_MISSING_POSTFIX_REQUEST = ResourcesUtils.loadResource("testresource_queuesplitter_configuration_missing_postfix_request", true);

    @Test
    public void testValidConfig(TestContext context) {

        // When
        ValidationResult validationResult = validate(CONFIG_RESOURCE_VALID);

        // Then
        context.assertNotNull(validationResult);
        context.assertEquals(ValidationStatus.VALIDATED_POSITIV, validationResult.getValidationStatus());
    }

    @Test
    public void testMissingPostfix(TestContext context) {

        // When
        ValidationResult validationResult = validate(CONFIG_RESOURCE_MISSING_POSTFIX);

        // Then
        context.assertNotNull(validationResult);
        context.assertEquals(ValidationStatus.VALIDATED_NEGATIV, validationResult.getValidationStatus());
        context.assertEquals("$.my-queue-[0-9]+.postfixFromStatic: is missing but it is required", extractErrorMessage(validationResult));
    }

    @Test
    public void testMissingPostfixRequest(TestContext context) {

        // When
        ValidationResult validationResult = validate(CONFIG_RESOURCE_MISSING_POSTFIX_REQUEST);

        // Then
        context.assertNotNull(validationResult);
        context.assertEquals(ValidationStatus.VALIDATED_NEGATIV, validationResult.getValidationStatus());
        context.assertEquals("$.my-queue-[0-9]+.postfixFromRequest.header: is missing but it is required", extractErrorMessage(validationResult));
    }

    private ValidationResult validate(String loggingResource) {
        return Validator.validateStatic(Buffer.buffer(loggingResource), CONFIG_SCHEMA, logger);
    }

    private String extractErrorMessage(ValidationResult validationResult){
        return validationResult.getValidationDetails().getJsonObject(0).getString("message");
    }

}

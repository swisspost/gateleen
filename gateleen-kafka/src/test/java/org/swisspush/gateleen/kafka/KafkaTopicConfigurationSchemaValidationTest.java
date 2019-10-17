package org.swisspush.gateleen.kafka;

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
 * Tests for the kafka topic configuration resource schema
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class KafkaTopicConfigurationSchemaValidationTest {

    private final String CONFIG_SCHEMA = ResourcesUtils.loadResource("gateleen_kafka_topic_configuration_schema", true);
    private final String VALID_CONFIG_RESOURCE = ResourcesUtils.loadResource("testresource_valid_kafka_topic_configuration", true);
    private final String MISSING_BOOTSTRAP_SERVERS_CONFIG_RESOURCE = ResourcesUtils.loadResource("testresource_kafka_topic_configuration_missing_bootstrap_servers", true);
    private final String MISSING_KEY_SERIALIZER_CONFIG_RESOURCE = ResourcesUtils.loadResource("testresource_kafka_topic_configuration_missing_key_serializer", true);
    private final String MISSING_VALUE_SERIALIZER_CONFIG_RESOURCE = ResourcesUtils.loadResource("testresource_kafka_topic_configuration_missing_value_serializer", true);
    private final String INVALID_TYPES_CONFIG_RESOURCE = ResourcesUtils.loadResource("testresource_kafka_topic_configuration_invalid_types", true);

    private final Logger logger = LoggerFactory.getLogger(KafkaTopicConfigurationSchemaValidationTest.class);

    @Test
    public void testValidConfig(TestContext context){
        ValidationResult validationResult = validate(VALID_CONFIG_RESOURCE);
        context.assertNotNull(validationResult);
        context.assertEquals(ValidationStatus.VALIDATED_POSITIV, validationResult.getValidationStatus());
    }

    @Test
    public void testMissingBootstrapServersConfig(TestContext context){
        ValidationResult validationResult = validate(MISSING_BOOTSTRAP_SERVERS_CONFIG_RESOURCE);
        context.assertNotNull(validationResult);
        context.assertEquals(ValidationStatus.VALIDATED_NEGATIV, validationResult.getValidationStatus());
        context.assertEquals("Validation failed", validationResult.getMessage());
        context.assertEquals("$.my.topic.*.bootstrap.servers: is missing but it is required", extractErrorMessage(validationResult));
    }

    @Test
    public void testMissingKeySerializerConfig(TestContext context){
        ValidationResult validationResult = validate(MISSING_KEY_SERIALIZER_CONFIG_RESOURCE);
        context.assertNotNull(validationResult);
        context.assertEquals(ValidationStatus.VALIDATED_NEGATIV, validationResult.getValidationStatus());
        context.assertEquals("Validation failed", validationResult.getMessage());
        context.assertEquals("$...key.serializer: is missing but it is required", extractErrorMessage(validationResult));
    }

    @Test
    public void testMissingValueSerializerConfig(TestContext context){
        ValidationResult validationResult = validate(MISSING_VALUE_SERIALIZER_CONFIG_RESOURCE);
        context.assertNotNull(validationResult);
        context.assertEquals(ValidationStatus.VALIDATED_NEGATIV, validationResult.getValidationStatus());
        context.assertEquals("Validation failed", validationResult.getMessage());
        context.assertEquals("$...value.serializer: is missing but it is required", extractErrorMessage(validationResult));
    }

    @Test
    public void testInvalidTypesConfig(TestContext context){
        ValidationResult validationResult = validate(INVALID_TYPES_CONFIG_RESOURCE);
        context.assertNotNull(validationResult);
        context.assertEquals(ValidationStatus.VALIDATED_NEGATIV, validationResult.getValidationStatus());
        context.assertEquals("Validation failed", validationResult.getMessage());
        context.assertEquals("$...someIntegerProperty: integer found, string expected", extractErrorMessage(validationResult));
    }

    private String extractErrorMessage(ValidationResult validationResult){
        return validationResult.getValidationDetails().getJsonObject(0).getString("message");
    }

    private ValidationResult validate(String loggingResource){
        return Validator.validateStatic(Buffer.buffer(loggingResource), CONFIG_SCHEMA, logger);
    }
}

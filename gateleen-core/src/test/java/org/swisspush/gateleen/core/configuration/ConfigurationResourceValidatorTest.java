package org.swisspush.gateleen.core.configuration;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link ConfigurationResourceValidator} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class ConfigurationResourceValidatorTest extends ConfigurationResourceTestBase {

    private ConfigurationResourceValidator configurationResourceValidator;

    @Test
    public void testValidateInvalidJson(TestContext context) {
        Async async = context.async();
        configurationResourceValidator = new ConfigurationResourceValidator(vertx);

        configurationResourceValidator.validateConfigurationResource(Buffer.buffer(INVALID_JSON_CONTENT), PERSON_SCHEMA, event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(ConfigurationResourceValidator.ValidationStatus.VALIDATED_NEGATIV, event.result().getValidationStatus());
            String expectedMessage = "Unable to parse json";
            context.assertEquals(expectedMessage, event.result().getMessage());
            async.complete();
        });
    }

    @Test
    public void testValidateValidJsonWithNoSchemaProvided(TestContext context) {
        Async async = context.async();
        configurationResourceValidator = new ConfigurationResourceValidator(vertx);

        configurationResourceValidator.validateConfigurationResource(Buffer.buffer(CONTENT_MATCHING_PERSON_SCHEMA), null, event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(ConfigurationResourceValidator.ValidationStatus.VALIDATED_POSITIV, event.result().getValidationStatus());
            async.complete();
        });
    }

    @Test
    public void testValidateValidJsonWithEmptySchemaProvided(TestContext context) {
        Async async = context.async();
        configurationResourceValidator = new ConfigurationResourceValidator(vertx);

        configurationResourceValidator.validateConfigurationResource(Buffer.buffer(CONTENT_MATCHING_PERSON_SCHEMA), "", event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(ConfigurationResourceValidator.ValidationStatus.VALIDATED_POSITIV, event.result().getValidationStatus());
            async.complete();
        });
    }

    @Test
    public void testValidateValidJsonWithInvalidSchemaProvided(TestContext context) {
        Async async = context.async();
        configurationResourceValidator = new ConfigurationResourceValidator(vertx);

        configurationResourceValidator.validateConfigurationResource(Buffer.buffer(CONTENT_MATCHING_PERSON_SCHEMA), INVALID_SCHEMA, event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(ConfigurationResourceValidator.ValidationStatus.VALIDATED_NEGATIV, event.result().getValidationStatus());
            String expectedMessage = "Unable to parse json schema";
            context.assertEquals(expectedMessage, event.result().getMessage());
            async.complete();
        });
    }

    @Test
    public void testValidateValidJsonWithInvalidSchemaMissingDeclarationProvided(TestContext context) {
        Async async = context.async();
        configurationResourceValidator = new ConfigurationResourceValidator(vertx);

        configurationResourceValidator.validateConfigurationResource(Buffer.buffer(CONTENT_MATCHING_PERSON_SCHEMA), INVALID_SCHEMA_MISSING_DECLARATION, event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(ConfigurationResourceValidator.ValidationStatus.VALIDATED_NEGATIV, event.result().getValidationStatus());
            String expectedMessage = "Invalid schema: Expected property '$schema'";
            context.assertTrue(event.result().getMessage().startsWith(expectedMessage));
            async.complete();
        });
    }

    @Test
    public void testValidateValidJsonWithContentNotMatchingSchema(TestContext context) {
        Async async = context.async();
        configurationResourceValidator = new ConfigurationResourceValidator(vertx);

        configurationResourceValidator.validateConfigurationResource(Buffer.buffer(CONTENT_NOT_MATCHING_PERSON_SCHEMA), PERSON_SCHEMA, event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(ConfigurationResourceValidator.ValidationStatus.VALIDATED_NEGATIV, event.result().getValidationStatus());
            String expectedMessage = "Validation failed";
            context.assertEquals(expectedMessage, event.result().getMessage());

            context.assertNotNull(event.result().getValidationDetails());
            JsonArray validationDetails = event.result().getValidationDetails();
            context.assertTrue(validationDetails.getJsonObject(0).encode().contains("\"missing\":[\"lastName\"]"));
            context.assertTrue(validationDetails.getJsonObject(0).encode().contains("\"message\":\"missing required property(ies)\""));

            async.complete();
        });
    }

    @Test
    public void testValidateValidJsonWithContentMatchingSchema(TestContext context) {
        Async async = context.async();
        configurationResourceValidator = new ConfigurationResourceValidator(vertx);

        configurationResourceValidator.validateConfigurationResource(Buffer.buffer(CONTENT_MATCHING_PERSON_SCHEMA), PERSON_SCHEMA, event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(ConfigurationResourceValidator.ValidationStatus.VALIDATED_POSITIV, event.result().getValidationStatus());
            async.complete();
        });
    }
}

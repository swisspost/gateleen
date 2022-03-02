package org.swisspush.gateleen.validation;

import com.google.common.util.concurrent.SettableFuture;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.validation.ValidationStatus;

import java.util.Optional;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;

@RunWith(VertxUnitRunner.class)
public class ValidatorTest extends AbstractTest {

    private MockResourceStorage storage;
    private ValidationSchemaProvider validationSchemaProvider;
    private final String SCHEMA_ROOT = "/foo/schemas/apis/";
    private Validator validator;
    private Logger logger;

    private final String SAMPLE_SCHEMA = "{\n" +
            "\t\"$schema\": \"http://json-schema.org/draft-04/schema#\",\n" +
            "\t\"type\": \"object\",\n" +
            "\t\"properties\": {\n" +
            "\t\t\"firstName\": {\n" +
            "\t\t\t\"type\": \"string\"\n" +
            "\t\t},\n" +
            "\t\t\"lastName\": {\n" +
            "\t\t\t\"type\": \"string\"\n" +
            "\t\t}\n" +
            "\t},\n" +
            "\t\"required\": [\"firstName\", \"lastName\"]\n" +
            "}";

    private final String CONTENT_MATCHING_SAMPLE_SCHEMA = "{\n" +
            "\t\"firstName\": \"John\",\n" +
            "\t\"lastName\": \"Doe\"\n" +
            "}";

    private final String CONTENT_NOT_MATCHING_SAMPLE_SCHEMA = "{\n" +
            "\t\"firstName\": \"John\",\n" +
            "\t\"someOtherProperty\": \"Doe\"\n" +
            "}";

    @Before
    public void setUp() {
        storage = new MockResourceStorage();
        validationSchemaProvider = Mockito.mock(ValidationSchemaProvider.class);
        validator = new Validator(storage, SCHEMA_ROOT, validationSchemaProvider);
        logger = Mockito.mock(Logger.class);

        storage.putMockData("/foo/schemas/apis/", "{\"apis\": [\"foo\"]}");
    }


    @Test
    public void testValidationWithVariables(TestContext context) {
        // add Data for lowdash replacement
        prepareSchema("{\n" +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\",    \n" +
                "    \"type\": \"object\"\n" +
                "}");

        CustomHttpServerRequest getValidationResourceRequest = new CustomHttpServerRequest(HttpMethod.GET, "/foo/mediadata/v1/specials/03");

        String type = "GET/out";
        Buffer jsonBuffer = Buffer.buffer();
        final SettableFuture<String> future = SettableFuture.create();
        validator.validate(getValidationResourceRequest, type, jsonBuffer, validationResult -> {
            String message = validationResult.getMessage();
            context.assertFalse(message.contains("Could not get path"), message);
            context.assertFalse(message.contains("No schema for"), message);
            context.assertTrue(message.contains("Invalid JSON"), message);// No correct mock schema - but this means it found it
            future.set(message);
        });
        context.assertTrue(future.isDone());
    }

    @Test
    public void testValidationWithNonValidResourceContent(TestContext context) {
        Async async = context.async();
        prepareSchema(SAMPLE_SCHEMA);

        CustomHttpServerRequest getValidationResourceRequest = new CustomHttpServerRequest(HttpMethod.GET, "/foo/mediadata/v1/specials/03");

        String type = "GET/out";
        Buffer jsonBuffer = Buffer.buffer(CONTENT_NOT_MATCHING_SAMPLE_SCHEMA);
        validator.validate(getValidationResourceRequest, type, jsonBuffer, validationResult -> {
            context.assertFalse(validationResult.isSuccess());
            context.assertEquals(ValidationStatus.VALIDATED_NEGATIV, validationResult.getValidationStatus());
            String message = validationResult.getMessage();
            context.assertFalse(message.contains("Could not get path"), message);
            context.assertFalse(message.contains("No schema for"), message);
            context.assertTrue(message.contains("Invalid JSON for /foo/mediadata/v1/specials/03"), message);
            context.assertTrue(message.contains("\"message\" : \"$.lastName: is missing but it is required\""), message);
            async.complete();
        });
    }

    @Test
    public void testValidationWithValidResourceContent(TestContext context) {
        Async async = context.async();
        prepareSchema(SAMPLE_SCHEMA);

        CustomHttpServerRequest getValidationResourceRequest = new CustomHttpServerRequest(HttpMethod.GET, "/foo/mediadata/v1/specials/03");

        String type = "GET/out";
        Buffer jsonBuffer = Buffer.buffer(CONTENT_MATCHING_SAMPLE_SCHEMA);
        validator.validate(getValidationResourceRequest, type, jsonBuffer, validationResult -> {
            context.assertTrue(validationResult.isSuccess(), "ValidationResult should be a success (VALIDATED_POSITIV)");
            context.assertEquals(ValidationStatus.VALIDATED_POSITIV, validationResult.getValidationStatus());
            context.assertNull(validationResult.getMessage(), "Message should be null when validation result was positive");
            async.complete();
        });
    }

    @Test
    public void testValidation(TestContext context) {
        // add Data for lowdash replacement
        storage.putMockData("/foo/schemas/apis/foo/", "{\"foo\": [\"mediamessage\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediamessage/", "{\"mediamessage\": [\"v1\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediamessage/v1/", "{\"v1\": [\"output\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediamessage/v1/output/", "{\"output\": [\"front\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediamessage/v1/output/front/", "{\"front\": [\"GET\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediamessage/v1/output/front/GET/", "{\"GET\": [\"out\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediamessage/v1/output/front/GET/out", "{\n" +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\",    \n" +
                "    \"type\": \"object\"\n" +
                "}");

        CustomHttpServerRequest getValidationResourceRequest = new CustomHttpServerRequest(HttpMethod.GET, "/foo/mediamessage/v1/output/front");

        String type = "GET/out";
        Buffer jsonBuffer = Buffer.buffer();
        final SettableFuture<String> future = SettableFuture.create();
        validator.validate(getValidationResourceRequest, type, jsonBuffer, validationResult -> {
            String message = validationResult.getMessage();
            context.assertFalse(message.contains("Could not get path"), message);
            context.assertFalse(message.contains("No schema for"), message);
            context.assertTrue(message.contains("Invalid JSON"), message);// No correct mock schema - but this means it found it
            future.set(message);
        });
        context.assertTrue(future.isDone());
    }

    @Test
    public void testValidationWithNoSchema(TestContext context) {
        // add Data for lowdash replacement
        storage.putMockData("/foo/schemas/apis/foo/", "{\"foo\": [\"mediamessage\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediamessage/", "{\"mediamessage\": [\"v1\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediamessage/v1/", "{\"v1\": [\"output\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediamessage/v1/output/", "{\"output\": [\"front\"]}");
        // schema line missing

        CustomHttpServerRequest getValidationResourceRequest = new CustomHttpServerRequest(HttpMethod.GET, "/foo/mediamessage/v1/output/front");

        String type = "GET/out";
        Buffer jsonBuffer = Buffer.buffer();
        final SettableFuture<String> future = SettableFuture.create();
        validator.validate(getValidationResourceRequest, type, jsonBuffer, validationResult -> {
            String message = validationResult.getMessage();
            context.assertTrue(message.contains("Could not get path"), message);
            future.set(message);
        });
        context.assertTrue(future.isDone());
    }

    @Test
    public void testValidationWithValidResourceContentAndPresentSchema(TestContext context) {
        Async async = context.async();
        when(validationSchemaProvider.schemaFromLocation(any(SchemaLocation.class)))
                .thenReturn(Future.succeededFuture(Optional.of(createSchema(SAMPLE_SCHEMA))));

        CustomHttpServerRequest getValidationResourceRequest = new CustomHttpServerRequest(HttpMethod.GET, "/foo/mediadata/v1/specials/03");

        String type = "GET/out";
        Buffer jsonBuffer = Buffer.buffer(CONTENT_MATCHING_SAMPLE_SCHEMA);
        validator.validate(getValidationResourceRequest, type, jsonBuffer, new SchemaLocation("/path/to/the/schema", null), validationResult -> {
            context.assertTrue(validationResult.isSuccess(), "ValidationResult should be a success (VALIDATED_POSITIV)");
            context.assertEquals(ValidationStatus.VALIDATED_POSITIV, validationResult.getValidationStatus());
            context.assertNull(validationResult.getMessage(), "Message should be null when validation result was positive");
            async.complete();
        });
    }

    @Test
    public void testValidationWithValidResourceContentAndMissingSchema(TestContext context) {
        Async async = context.async();
        when(validationSchemaProvider.schemaFromLocation(any(SchemaLocation.class)))
                .thenReturn(Future.succeededFuture(Optional.empty()));

        CustomHttpServerRequest getValidationResourceRequest = new CustomHttpServerRequest(HttpMethod.GET, "/foo/mediadata/v1/specials/03");

        String type = "GET/out";
        Buffer jsonBuffer = Buffer.buffer(CONTENT_MATCHING_SAMPLE_SCHEMA);
        validator.validate(getValidationResourceRequest, type, jsonBuffer, new SchemaLocation("/path/to/the/schema", null), validationResult -> {
            context.assertFalse(validationResult.isSuccess(), "ValidationResult should not be a success (COULD_NOT_VALIDATE)");
            context.assertEquals(ValidationStatus.COULD_NOT_VALIDATE, validationResult.getValidationStatus());
            context.assertEquals("No schema found in location /path/to/the/schema", validationResult.getMessage());
            async.complete();
        });
    }

    @Test
    public void testValidationWithValidResourceContentAndSchemaProviderError(TestContext context) {
        Async async = context.async();
        when(validationSchemaProvider.schemaFromLocation(any(SchemaLocation.class)))
                .thenReturn(Future.failedFuture("Boooom"));

        CustomHttpServerRequest getValidationResourceRequest = new CustomHttpServerRequest(HttpMethod.GET, "/foo/mediadata/v1/specials/03");

        String type = "GET/out";
        Buffer jsonBuffer = Buffer.buffer(CONTENT_MATCHING_SAMPLE_SCHEMA);
        validator.validate(getValidationResourceRequest, type, jsonBuffer, new SchemaLocation("/path/to/the/schema", null), validationResult -> {
            context.assertFalse(validationResult.isSuccess(), "ValidationResult should not be a success (COULD_NOT_VALIDATE)");
            context.assertEquals(ValidationStatus.COULD_NOT_VALIDATE, validationResult.getValidationStatus());
            context.assertEquals("Error while getting schema. Cause: Boooom", validationResult.getMessage());
            async.complete();
        });
    }

    @Test
    public void testValidationWithNonValidResourceContentAndPresentSchema(TestContext context) {
        Async async = context.async();

        when(validationSchemaProvider.schemaFromLocation(any(SchemaLocation.class)))
                .thenReturn(Future.succeededFuture(Optional.of(createSchema(SAMPLE_SCHEMA))));

        CustomHttpServerRequest getValidationResourceRequest = new CustomHttpServerRequest(HttpMethod.GET, "/foo/mediadata/v1/specials/03");

        String type = "GET/out";
        Buffer jsonBuffer = Buffer.buffer(CONTENT_NOT_MATCHING_SAMPLE_SCHEMA);
        validator.validate(getValidationResourceRequest, type, jsonBuffer, new SchemaLocation("/path/to/the/schema", null), validationResult -> {
            context.assertFalse(validationResult.isSuccess());
            context.assertEquals(ValidationStatus.VALIDATED_NEGATIV, validationResult.getValidationStatus());
            String message = validationResult.getMessage();
            context.assertFalse(message.contains("Could not get path"), message);
            context.assertFalse(message.contains("No schema for"), message);
            context.assertTrue(message.contains("Invalid JSON for /foo/mediadata/v1/specials/03"), message);
            context.assertTrue(message.contains("\"message\" : \"$.lastName: is missing but it is required\""), message);
            async.complete();
        });
    }

    @Test
    public void testValidateWithSchemaLocation(TestContext context) {
        Async async = context.async();

        when(validationSchemaProvider.schemaFromLocation(any(SchemaLocation.class)))
                .thenReturn(Future.succeededFuture(Optional.of(createSchema(SAMPLE_SCHEMA))));

        Buffer jsonBuffer = Buffer.buffer(CONTENT_MATCHING_SAMPLE_SCHEMA);
        validator.validateWithSchemaLocation(new SchemaLocation("/path/to/the/schema", null), jsonBuffer, logger)
                .onComplete(validationResult -> {
                    context.assertTrue(validationResult.succeeded());
                    context.assertEquals(ValidationStatus.VALIDATED_POSITIV, validationResult.result().getValidationStatus());
                    async.complete();
                });
    }

    @Test
    public void testValidateWithSchemaLocationAndSchemaProviderError(TestContext context) {
        Async async = context.async();
        when(validationSchemaProvider.schemaFromLocation(any(SchemaLocation.class)))
                .thenReturn(Future.failedFuture("Boooom"));

        Buffer jsonBuffer = Buffer.buffer(CONTENT_MATCHING_SAMPLE_SCHEMA);
        validator.validateWithSchemaLocation(new SchemaLocation("/path/to/the/schema", null), jsonBuffer, logger)
                .onComplete(validationResult -> {
                    context.assertFalse(validationResult.result().isSuccess());
                    context.assertEquals(ValidationStatus.COULD_NOT_VALIDATE, validationResult.result().getValidationStatus());
                    context.assertEquals("Error while getting schema. Cause: Boooom", validationResult.result().getMessage());
                    async.complete();
                });
    }

    @Test
    public void testValidateWithSchemaLocationAndMissingSchema(TestContext context) {
        Async async = context.async();
        when(validationSchemaProvider.schemaFromLocation(any(SchemaLocation.class)))
                .thenReturn(Future.succeededFuture(Optional.empty()));

        Buffer jsonBuffer = Buffer.buffer(CONTENT_MATCHING_SAMPLE_SCHEMA);

        validator.validateWithSchemaLocation(new SchemaLocation("/path/to/the/schema", null), jsonBuffer, logger)
                .onComplete(validationResult -> {
                    context.assertFalse(validationResult.result().isSuccess(), "ValidationResult should not be a success (COULD_NOT_VALIDATE)");
                    context.assertEquals(ValidationStatus.COULD_NOT_VALIDATE, validationResult.result().getValidationStatus());
                    context.assertEquals("No schema found in location /path/to/the/schema", validationResult.result().getMessage());
                    async.complete();
                });
    }

    @Test
    public void testValidateWithSchemaLocationWithNonValidResourceContentAndPresentSchema(TestContext context) {
        Async async = context.async();

        when(validationSchemaProvider.schemaFromLocation(any(SchemaLocation.class)))
                .thenReturn(Future.succeededFuture(Optional.of(createSchema(SAMPLE_SCHEMA))));

        Buffer jsonBuffer = Buffer.buffer(CONTENT_NOT_MATCHING_SAMPLE_SCHEMA);

        validator.validateWithSchemaLocation(new SchemaLocation("/path/to/the/schema", null), jsonBuffer, logger)
                .onComplete(validationResult -> {
                    context.assertFalse(validationResult.result().isSuccess());
                    context.assertEquals(ValidationStatus.VALIDATED_NEGATIV, validationResult.result().getValidationStatus());
                    String message = validationResult.result().getMessage();
                    context.assertFalse(message.contains("Could not get path"), message);
                    context.assertFalse(message.contains("No schema for"), message);
                    context.assertTrue(message.contains("Invalid JSON for /path/to/the/schema"), message);
                    context.assertTrue(message.contains("\"message\" : \"$.lastName: is missing but it is required\""), message);
                    async.complete();
                });
    }

    private void prepareSchema(String schemaJson) {
        storage.putMockData("/foo/schemas/apis/foo/", "{\"foo\": [\"mediadata\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediadata/", "{\"mediadata\": [\"v1\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediadata/v1/", "{\"v1\": [\"specials\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediadata/v1/specials/", "{\"specials\": [\"_\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediadata/v1/specials/_/", "{\"_\": [\"GET\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediadata/v1/specials/_/GET/", "{\"GET\": [\"out\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediadata/v1/specials/_/GET/out", schemaJson);
    }
}

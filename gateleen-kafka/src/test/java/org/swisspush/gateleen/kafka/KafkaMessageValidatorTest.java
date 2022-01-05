package org.swisspush.gateleen.kafka;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.validation.ValidationResult;
import org.swisspush.gateleen.core.validation.ValidationStatus;
import org.swisspush.gateleen.validation.ValidationResource;
import org.swisspush.gateleen.validation.ValidationResourceManager;
import org.swisspush.gateleen.validation.Validator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

/**
 * Test class for the {@link KafkaMessageValidator}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class KafkaMessageValidatorTest {

    private Vertx vertx;
    private KafkaMessageValidator messageValidator;
    private Validator validator;
    private ValidationResourceManager validationResourceManager;

    @Before
    public void setUp() {
        vertx = Vertx.vertx();
        validationResourceManager = Mockito.mock(ValidationResourceManager.class);
        validator = Mockito.mock(Validator.class);

        messageValidator = new KafkaMessageValidator(validationResourceManager, validator);
    }

    @Test
    public void testValidateMessagesWithEmptyRecordsList(TestContext context) {
        Async async = context.async();

        HttpServerResponse response = spy(new StreamingResponse(new CaseInsensitiveHeaders()));
        StreamingRequest request = new StreamingRequest(HttpMethod.GET, "/path/to/myTopic", "", new CaseInsensitiveHeaders(), response);

        messageValidator.validateMessages(request, Collections.emptyList()).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(ValidationStatus.VALIDATED_POSITIV, event.result().getValidationStatus());
            verifyZeroInteractions(validationResourceManager);
            verifyZeroInteractions(validator);
            async.complete();
        });

    }

    @Test
    public void testValidateMessagesNoMatchingValidationResourceEntry(TestContext context) {
        Async async = context.async();

        when(validationResourceManager.getValidationResource()).thenReturn(new ValidationResource());

        HttpServerResponse response = spy(new StreamingResponse(new CaseInsensitiveHeaders()));
        StreamingRequest request = new StreamingRequest(HttpMethod.GET, "/path/to/myTopic", "", new CaseInsensitiveHeaders(), response);

        List<KafkaProducerRecord<String, String>> kafkaProducerRecords = new ArrayList<>();
        kafkaProducerRecords.add(KafkaProducerRecord.create("myOtherTopic", "{}"));

        messageValidator.validateMessages(request, kafkaProducerRecords).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(ValidationStatus.VALIDATED_POSITIV, event.result().getValidationStatus());
            verify(validationResourceManager, times(1)).getValidationResource();
            verifyZeroInteractions(validator);
            async.complete();
        });

    }

    @Test
    public void testValidateMessagesMatchingValidationResourceEntryWithoutSchemaLocation(TestContext context) {
        Async async = context.async();

        ValidationResource validationResource = new ValidationResource();
        validationResource.addResource(Map.of(ValidationResource.METHOD_PROPERTY, "PUT", ValidationResource.URL_PROPERTY, "/path/to/myTopic"));

        when(validationResourceManager.getValidationResource()).thenReturn(validationResource);

        HttpServerResponse response = spy(new StreamingResponse(new CaseInsensitiveHeaders()));
        StreamingRequest request = new StreamingRequest(HttpMethod.PUT, "/path/to/myTopic", "", new CaseInsensitiveHeaders(), response);

        List<KafkaProducerRecord<String, String>> kafkaProducerRecords = new ArrayList<>();
        kafkaProducerRecords.add(KafkaProducerRecord.create("myOtherTopic", "{}"));

        messageValidator.validateMessages(request, kafkaProducerRecords).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(ValidationStatus.COULD_NOT_VALIDATE, event.result().getValidationStatus());
            verify(validationResourceManager, times(2)).getValidationResource();
            verifyZeroInteractions(validator);
            async.complete();
        });

    }

    @Test
    public void testValidateMessagesMatchingValidationResourceEntry(TestContext context) {
        Async async = context.async();

        ValidationResource validationResource = new ValidationResource();
        validationResource.addResource(
                Map.of(ValidationResource.METHOD_PROPERTY, "PUT",
                ValidationResource.URL_PROPERTY, "/path/to/myTopic",
                ValidationResource.SCHEMA_LOCATION_PROPERTY, "/path/to/schema"
                ));

        when(validationResourceManager.getValidationResource()).thenReturn(validationResource);

        HttpServerResponse response = spy(new StreamingResponse(new CaseInsensitiveHeaders()));
        StreamingRequest request = new StreamingRequest(HttpMethod.PUT, "/path/to/myTopic", "", new CaseInsensitiveHeaders(), response);

        List<KafkaProducerRecord<String, String>> kafkaProducerRecords = new ArrayList<>();
        kafkaProducerRecords.add(KafkaProducerRecord.create("myOtherTopic", "{}"));

        when(validator.validateWithSchemaLocation(any(), any(), any())).thenReturn(
                Future.succeededFuture(new ValidationResult(ValidationStatus.COULD_NOT_VALIDATE, "Error while getting schema.")));

        messageValidator.validateMessages(request, kafkaProducerRecords).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(ValidationStatus.COULD_NOT_VALIDATE, event.result().getValidationStatus());
            verify(validationResourceManager, times(2)).getValidationResource();
            verify(validator, times(1)).validateWithSchemaLocation(any(), any(), any());
            async.complete();
        });

    }

    @Test
    public void testValidateMessagesWithFailInValidator(TestContext context) {
        Async async = context.async();

        ValidationResource validationResource = new ValidationResource();
        validationResource.addResource(
                Map.of(ValidationResource.METHOD_PROPERTY, "PUT",
                        ValidationResource.URL_PROPERTY, "/path/to/myTopic",
                        ValidationResource.SCHEMA_LOCATION_PROPERTY, "/path/to/schema"
                ));

        when(validationResourceManager.getValidationResource()).thenReturn(validationResource);

        HttpServerResponse response = spy(new StreamingResponse(new CaseInsensitiveHeaders()));
        StreamingRequest request = new StreamingRequest(HttpMethod.PUT, "/path/to/myTopic", "", new CaseInsensitiveHeaders(), response);

        String payload_1 = new JsonObject().encode();
        String payload_2 = new JsonObject().put("foo", "bar").encode();
        List<KafkaProducerRecord<String, String>> kafkaProducerRecords = new ArrayList<>();
        kafkaProducerRecords.add(KafkaProducerRecord.create("myOtherTopic", payload_1));
        kafkaProducerRecords.add(KafkaProducerRecord.create("myOtherTopic", payload_2));

        when(validator.validateWithSchemaLocation(any(), eq(Buffer.buffer(payload_1)), any())).thenReturn(Future.failedFuture("Boooom"));
        when(validator.validateWithSchemaLocation(any(), eq(Buffer.buffer(payload_2)), any())).thenReturn(
                Future.succeededFuture(new ValidationResult(ValidationStatus.VALIDATED_POSITIV)));

        messageValidator.validateMessages(request, kafkaProducerRecords).setHandler(event -> {
            context.assertTrue(event.failed());
            verify(validationResourceManager, times(2)).getValidationResource();
            verify(validator, times(2)).validateWithSchemaLocation(any(), any(), any());
            async.complete();
        });

    }

    @Test
    public void testValidateMultipleMessages(TestContext context) {
        Async async = context.async();

        ValidationResource validationResource = new ValidationResource();
        validationResource.addResource(
                Map.of(ValidationResource.METHOD_PROPERTY, "PUT",
                        ValidationResource.URL_PROPERTY, "/path/to/myTopic",
                        ValidationResource.SCHEMA_LOCATION_PROPERTY, "/path/to/schema"
                ));

        when(validationResourceManager.getValidationResource()).thenReturn(validationResource);

        HttpServerResponse response = spy(new StreamingResponse(new CaseInsensitiveHeaders()));
        StreamingRequest request = new StreamingRequest(HttpMethod.PUT, "/path/to/myTopic", "", new CaseInsensitiveHeaders(), response);

        String payload_1 = new JsonObject().encode();
        String payload_2 = new JsonObject().put("foo", "bar").encode();
        String payload_3 = new JsonObject().put("abc", "def").encode();
        List<KafkaProducerRecord<String, String>> kafkaProducerRecords = new ArrayList<>();
        kafkaProducerRecords.add(KafkaProducerRecord.create("myOtherTopic", payload_1));
        kafkaProducerRecords.add(KafkaProducerRecord.create("myOtherTopic", payload_2));
        kafkaProducerRecords.add(KafkaProducerRecord.create("myOtherTopic", payload_3));

        when(validator.validateWithSchemaLocation(any(), any(), any())).thenReturn(
                Future.succeededFuture(new ValidationResult(ValidationStatus.VALIDATED_POSITIV)));

        messageValidator.validateMessages(request, kafkaProducerRecords).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(ValidationStatus.VALIDATED_POSITIV, event.result().getValidationStatus());
            verify(validationResourceManager, times(2)).getValidationResource();
            verify(validator, times(3)).validateWithSchemaLocation(any(), any(), any());
            async.complete();
        });

    }
}

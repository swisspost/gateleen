package org.swisspush.gateleen.kafka;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.validation.ValidationResult;
import org.swisspush.gateleen.core.validation.ValidationStatus;
import org.swisspush.gateleen.validation.SchemaLocation;
import org.swisspush.gateleen.validation.ValidationResourceManager;
import org.swisspush.gateleen.validation.ValidationUtil;
import org.swisspush.gateleen.validation.Validator;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.stream.Collectors.toList;

public class KafkaMessageValidator {

    private final ValidationResourceManager validationResourceManager;
    private final Validator validator;
    private final Logger log = LoggerFactory.getLogger(KafkaHandler.class);

    public KafkaMessageValidator(ValidationResourceManager validationResourceManager, Validator validator) {
        this.validationResourceManager = validationResourceManager;
        this.validator = validator;
    }

    public Future<ValidationResult> validateMessages(HttpServerRequest request, List<KafkaProducerRecord<String, String>> kafkaProducerRecords) {
        if (kafkaProducerRecords.isEmpty()) {
            return Future.succeededFuture(new ValidationResult(ValidationStatus.VALIDATED_POSITIV));
        }

        Map<String, String> entry = ValidationUtil.matchingValidationResourceEntry(validationResourceManager.getValidationResource(), request, log);
        if (entry == null) {
            return Future.succeededFuture(new ValidationResult(ValidationStatus.VALIDATED_POSITIV));
        }

        Optional<SchemaLocation> optionalSchemaLocation = ValidationUtil.matchingSchemaLocation(validationResourceManager.getValidationResource(), request, log);
        if (optionalSchemaLocation.isEmpty()) {
            log.warn("No schema location found for {}. Could not validate kafka message", request.uri());
            return Future.succeededFuture(new ValidationResult(ValidationStatus.COULD_NOT_VALIDATE));
        }

        SchemaLocation schemaLocation = optionalSchemaLocation.get();

        @SuppressWarnings("rawtypes") //https://github.com/eclipse-vertx/vert.x/issues/2627
        List<Future> futures = kafkaProducerRecords.stream()
                .map(message -> validator.validateWithSchemaLocation(schemaLocation, Buffer.buffer(message.value()), log))
                .collect(toList());

        return CompositeFuture.all(futures).compose(compositeFuture -> {
            for (Object o : compositeFuture.list()) {
                if (((ValidationResult) o).getValidationStatus() != ValidationStatus.VALIDATED_POSITIV) {
                    return Future.succeededFuture((ValidationResult) o);
                }
            }
            return Future.succeededFuture(new ValidationResult(ValidationStatus.VALIDATED_POSITIV));
        });
    }
}

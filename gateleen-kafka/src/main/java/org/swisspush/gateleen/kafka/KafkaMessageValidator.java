package org.swisspush.gateleen.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.stream.Collectors.toList;

public class KafkaMessageValidator {

    private final ValidationResourceManager validationResourceManager;
    private final Validator validator;
    private final Logger log = LoggerFactory.getLogger(KafkaHandler.class);
    private final Vertx vertx;

    private MeterRegistry meterRegistry;
    private final Map<String, Counter> failedToValidateCounterMap = new HashMap<>();

    public static final String FAIL_VALIDATION_MESSAGES_METRIC = "gateleen.kafka.validation.fail.messages";
    public static final String FAIL_VALIDATION_MESSAGES_METRIC_DESCRIPTION = "Amount of failed kafka message validations";
    public static final String TOPIC = "topic";

    public KafkaMessageValidator(Vertx vertx, ValidationResourceManager validationResourceManager, Validator validator) {
        this.vertx = vertx;
        this.validationResourceManager = validationResourceManager;
        this.validator = validator;
    }

    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        failedToValidateCounterMap.clear();
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

        String topic = kafkaProducerRecords.get(0).topic();

        @SuppressWarnings("rawtypes") //https://github.com/eclipse-vertx/vert.x/issues/2627
        List<Future> futures = kafkaProducerRecords.stream()
                .map(message -> validator.validateWithSchemaLocation(schemaLocation, Buffer.buffer(message.value()), log))
                .collect(toList());

        return CompositeFuture.all(futures).compose(compositeFuture -> {
            for (Object o : compositeFuture.list()) {
                if (((ValidationResult) o).getValidationStatus() != ValidationStatus.VALIDATED_POSITIV) {
                    return vertx.executeBlocking(() -> {
                        incrementValidationFailCount(topic);
                        return ((ValidationResult) o);
                    });
                }
            }
            return Future.succeededFuture(new ValidationResult(ValidationStatus.VALIDATED_POSITIV));
        },  throwable -> vertx.executeBlocking(event -> {
            incrementValidationFailCount(topic);
            event.fail(throwable);
        }));

    }

    private void incrementValidationFailCount(String topic) {
        Counter counter = failedToValidateCounterMap.get(topic);
        if(counter != null) {
            counter.increment();
            return;
        }

        if(meterRegistry != null) {
            Counter newCounter = Counter.builder(FAIL_VALIDATION_MESSAGES_METRIC)
                    .description(FAIL_VALIDATION_MESSAGES_METRIC_DESCRIPTION)
                    .tag(TOPIC, topic)
                    .register(meterRegistry);
            newCounter.increment();
            failedToValidateCounterMap.put(topic, newCounter);
        }
    }
}

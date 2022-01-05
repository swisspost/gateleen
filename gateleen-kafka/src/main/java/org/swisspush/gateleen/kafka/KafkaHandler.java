package org.swisspush.gateleen.kafka;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceConsumer;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.core.validation.ValidationResult;
import org.swisspush.gateleen.core.validation.ValidationStatus;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Handler class for all Kafka related requests.
 *
 * The main responsibilities for this handler are:
 * <ul>
 * <li>Manage kafka configuration resource</li>
 * <li>Manage the lifecycle of {@link KafkaProducer} based on the kafka configuration resource</li>
 * <li>Convert requests to messages and forward them to kafka</li>
 * </ul>
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class KafkaHandler extends ConfigurationResourceConsumer {

    private final Logger log = LoggerFactory.getLogger(KafkaHandler.class);

    private final String streamingPath;
    private final KafkaProducerRepository repository;
    private final KafkaTopicExtractor topicExtractor;
    private final KafkaMessageSender kafkaMessageSender;
    private final Map<String, Object> properties;
    private KafkaMessageValidator kafkaMessageValidator;

    private boolean initialized = false;

    public KafkaHandler(ConfigurationResourceManager configurationResourceManager, KafkaProducerRepository repository,
                        KafkaMessageSender kafkaMessageSender, String configResourceUri, String streamingPath) {
        this(configurationResourceManager, null, repository, kafkaMessageSender,
                configResourceUri, streamingPath);
    }

    public KafkaHandler(ConfigurationResourceManager configurationResourceManager, KafkaMessageValidator kafkaMessageValidator,
                        KafkaProducerRepository repository, KafkaMessageSender kafkaMessageSender, String configResourceUri,
                        String streamingPath) {
        super(configurationResourceManager, configResourceUri, "gateleen_kafka_topic_configuration_schema");
        this.kafkaMessageValidator = kafkaMessageValidator;
        this.repository = repository;
        this.kafkaMessageSender = kafkaMessageSender;
        this.streamingPath = streamingPath;
        this.properties = new HashMap<>();

        this.topicExtractor = new KafkaTopicExtractor(streamingPath);
    }

    public KafkaHandler(ConfigurationResourceManager configurationResourceManager, KafkaProducerRepository repository,
    KafkaMessageSender kafkaMessageSender, String configResourceUri, String streamingPath, Map<String, Object> properties) {

        super(configurationResourceManager, configResourceUri, "gateleen_kafka_topic_configuration_schema");
        this.repository = repository;
        this.kafkaMessageSender = kafkaMessageSender;
        this.streamingPath = streamingPath;
        this.properties = properties;

        this.topicExtractor = new KafkaTopicExtractor(streamingPath);
    }

    public Future<Void> initialize() {
        Future<Void> future = Future.future();
        configurationResourceManager().getRegisteredResource(configResourceUri()).setHandler(event -> {
            if (event.succeeded() && event.result().isPresent()) {
                initializeKafkaConfiguration(event.result().get()).setHandler(event1 -> future.complete());
            } else {
                log.warn("No kafka configuration resource with uri '{}' found. Unable to setup kafka configuration correctly", configResourceUri());
                future.complete();
            }
        });
        return future;
    }

    public boolean isInitialized() {
        return initialized;
    }

    private Future<Void> initializeKafkaConfiguration(Buffer configuration) {
        Future<Void> future = Future.future();
        final List<KafkaConfiguration> kafkaConfigurations = KafkaConfigurationParser.parse(configuration, properties);
        repository.closeAll().setHandler(event -> {
            for (KafkaConfiguration kafkaConfiguration : kafkaConfigurations) {
                repository.addKafkaProducer(kafkaConfiguration);
            }
            initialized = true;
            future.complete();
        });

        return future;
    }

    public boolean handle(final HttpServerRequest request) {
        if (request.uri().startsWith(streamingPath)) {
            RequestLoggerFactory.getLogger(KafkaHandler.class, request).info("Handling {}", request.uri());

            if (HttpMethod.POST != request.method()) {
                respondWith(StatusCode.METHOD_NOT_ALLOWED, StatusCode.METHOD_NOT_ALLOWED.getStatusMessage(), request);
                return true;
            }

            final Optional<String> optTopic = topicExtractor.extractTopic(request);
            if(optTopic.isEmpty()){
                respondWith(StatusCode.BAD_REQUEST, "Could not extract topic from request uri", request);
                return true;
            }

            String topic = optTopic.get();
            final Optional<Pair<KafkaProducer<String, String>, Pattern>> optProducer = repository.findMatchingKafkaProducer(topic);
            if(optProducer.isEmpty()){
                respondWith(StatusCode.NOT_FOUND, "Could not find a matching producer for topic " + topic, request);
                return true;
            }

            request.bodyHandler(payload -> {
                try {
                    log.debug("incoming kafka message payload: {}", payload.toString());
                    final List<KafkaProducerRecord<String, String>> kafkaProducerRecords = KafkaProducerRecordBuilder.buildRecords(topic, payload);
                    maybeValidate(request, kafkaProducerRecords).setHandler(validationEvent -> {
                        if(validationEvent.succeeded()) {
                            if(validationEvent.result().isSuccess()) {
                                kafkaMessageSender.sendMessages(optProducer.get().getLeft(), kafkaProducerRecords).setHandler(event -> {
                                    if(event.succeeded()) {
                                        RequestLoggerFactory.getLogger(KafkaHandler.class, request)
                                                .info("Successfully sent {} message(s) to kafka topic '{}'", kafkaProducerRecords.size(), topic);
                                        respondWith(StatusCode.OK, StatusCode.OK.getStatusMessage(), request);
                                    } else {
                                        respondWith(StatusCode.INTERNAL_SERVER_ERROR, event.cause().getMessage(), request);
                                    }
                                });
                            } else {
                                respondWith(StatusCode.BAD_REQUEST, validationEvent.result().getMessage(), request);
                            }
                        } else {
                            respondWith(StatusCode.INTERNAL_SERVER_ERROR, validationEvent.cause().getMessage(), request);
                        }
                    });
                } catch (ValidationException ve){
                    respondWith(StatusCode.BAD_REQUEST, ve.getMessage(), request);
                }
            });
            return true;
        }
        return false;
    }

    @Override
    public void resourceChanged(String resourceUri, Buffer resource) {
        if (configResourceUri() != null && configResourceUri().equals(resourceUri)) {
            log.info("Kafka configuration resource " + resourceUri + " was updated. Going to initialize with new configuration");
            initializeKafkaConfiguration(resource);
        }
    }

    @Override
    public void resourceRemoved(String resourceUri) {
        if (configResourceUri() != null && configResourceUri().equals(resourceUri)) {
            log.info("Kafka configuration resource " + resourceUri + " was removed. Going to close all kafka producers");
            repository.closeAll().setHandler(event -> initialized = false);
        }
    }

    private Future<ValidationResult> maybeValidate(HttpServerRequest request, List<KafkaProducerRecord<String, String>> kafkaProducerRecords) {
        if(kafkaMessageValidator != null) {
            return kafkaMessageValidator.validateMessages(request, kafkaProducerRecords);
        }
        return Future.succeededFuture(new ValidationResult(ValidationStatus.VALIDATED_POSITIV));
    }

    private void respondWith(StatusCode statusCode, String responseMessage, HttpServerRequest request) {
        ResponseStatusCodeLogUtil.info(request, statusCode, KafkaHandler.class);
        if(statusCode != StatusCode.OK) {
            RequestLoggerFactory.getLogger(KafkaHandler.class, request).info("Response message is: {}", responseMessage);
        }
        request.response().setStatusCode(statusCode.getStatusCode());
        request.response().setStatusMessage(statusCode.getStatusMessage());
        request.response().end(responseMessage);
    }
}

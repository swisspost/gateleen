package org.swisspush.gateleen.kafka;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
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
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;
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

import static org.swisspush.gateleen.core.exception.GateleenExceptionFactory.newGateleenThriftyExceptionFactory;

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
    private final GateleenExceptionFactory exceptionFactory;
    private final KafkaProducerRepository repository;
    private final KafkaTopicExtractor topicExtractor;
    private final KafkaMessageSender kafkaMessageSender;
    private final Map<String, Object> properties;
    private final KafkaProducerRecordBuilder kafkaProducerRecordBuilder;
    private KafkaMessageValidator kafkaMessageValidator;

    private boolean initialized = false;

    /** @deprecated Use {@link #builder()} */
    @Deprecated
    public KafkaHandler(ConfigurationResourceManager configurationResourceManager, KafkaProducerRepository repository,
                        KafkaMessageSender kafkaMessageSender, String configResourceUri, String streamingPath) {
        this(configurationResourceManager, null, repository, kafkaMessageSender,
                configResourceUri, streamingPath);
    }

    /** @deprecated Use {@link #builder()} */
    @Deprecated
    public KafkaHandler(ConfigurationResourceManager configurationResourceManager, KafkaMessageValidator kafkaMessageValidator,
                        KafkaProducerRepository repository, KafkaMessageSender kafkaMessageSender, String configResourceUri,
                        String streamingPath) {
        this(configurationResourceManager, kafkaMessageValidator, repository, kafkaMessageSender,
                configResourceUri, streamingPath, new HashMap<>());
    }

    /** @deprecated Use {@link #builder()} */
    @Deprecated
    public KafkaHandler(ConfigurationResourceManager configurationResourceManager, KafkaProducerRepository repository,
    KafkaMessageSender kafkaMessageSender, String configResourceUri, String streamingPath, Map<String, Object> properties) {

        this(configurationResourceManager, null, repository, kafkaMessageSender,
                configResourceUri, streamingPath, properties);
    }

    /** @deprecated Use {@link #builder()} */
    @Deprecated
    public KafkaHandler(ConfigurationResourceManager configurationResourceManager, KafkaMessageValidator kafkaMessageValidator, KafkaProducerRepository repository,
                        KafkaMessageSender kafkaMessageSender, String configResourceUri, String streamingPath, Map<String, Object> properties) {
        this(Vertx.vertx(), newGateleenThriftyExceptionFactory(), configurationResourceManager,
            kafkaMessageValidator, repository, kafkaMessageSender, configResourceUri, streamingPath,
            properties);
        log.warn("TODO: Do NOT use this DEPRECATED constructor! It creates instances that it should not create!");
    }

    public KafkaHandler(
        Vertx vertx,
        GateleenExceptionFactory exceptionFactory,
        ConfigurationResourceManager configurationResourceManager,
        KafkaMessageValidator kafkaMessageValidator,
        KafkaProducerRepository repository,
        KafkaMessageSender kafkaMessageSender,
        String configResourceUri,
        String streamingPath,
        Map<String, Object> properties
    ) {
        super(configurationResourceManager, configResourceUri, "gateleen_kafka_topic_configuration_schema");
        this.exceptionFactory = exceptionFactory;
        this.repository = repository;
        this.kafkaMessageValidator = kafkaMessageValidator;
        this.kafkaMessageSender = kafkaMessageSender;
        this.streamingPath = streamingPath;
        this.properties = properties;

        this.topicExtractor = new KafkaTopicExtractor(streamingPath);
        this.kafkaProducerRecordBuilder = new KafkaProducerRecordBuilder(vertx, exceptionFactory);
    }

    public static KafkaHandlerBuilder builder() {
        return new KafkaHandlerBuilder();
    }

    public Future<Void> initialize() {
        Promise<Void> promise = Promise.promise();
        configurationResourceManager().getRegisteredResource(configResourceUri()).onComplete((event -> {
            if (event.succeeded() && event.result().isPresent()) {
                initializeKafkaConfiguration(event.result().get()).onComplete((event1 -> promise.complete()));
            } else {
                log.warn("No kafka configuration resource with uri '{}' found. Unable to setup kafka configuration correctly", configResourceUri());
                promise.complete();
            }
        }));
        return promise.future();
    }

    public boolean isInitialized() {
        return initialized;
    }

    private Future<Void> initializeKafkaConfiguration(Buffer configuration) {
        Promise<Void> promise = Promise.promise();
        final List<KafkaConfiguration> kafkaConfigurations = KafkaConfigurationParser.parse(configuration, properties);



        repository.closeAll().future().onComplete((event -> {
            for (KafkaConfiguration kafkaConfiguration : kafkaConfigurations) {
                repository.addKafkaProducer(kafkaConfiguration);
            }
            initialized = true;
            promise.complete();
        }));

        return promise.future();
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
                log.debug("incoming kafka message payload: {}", payload);
                // TODO refactor away this callback-hell (Counts for the COMPLETE method
                //      surrounding this line, named 'KafkaHandler.handle()', NOT only
                //      those lines below).
                boolean[] isResponseSent = {false};
                kafkaProducerRecordBuilder.buildRecordsAsync(topic, payload).compose((List<KafkaProducerRecord<String, String>> kafkaProducerRecords) -> {
                    var fut =  maybeValidate(request, kafkaProducerRecords).compose(validationEvent -> {
                        if(validationEvent.isSuccess()) {
                            kafkaMessageSender.sendMessages(optProducer.get().getLeft(), kafkaProducerRecords).onComplete(event -> {
                                if(event.succeeded()) {
                                    RequestLoggerFactory.getLogger(KafkaHandler.class, request)
                                            .info("Successfully sent {} message(s) to kafka topic '{}'", kafkaProducerRecords.size(), topic);
                                    isResponseSent[0] = true;
                                    respondWith(StatusCode.OK, StatusCode.OK.getStatusMessage(), request);
                                } else {
                                    isResponseSent[0] = true;
                                    respondWith(StatusCode.INTERNAL_SERVER_ERROR, event.cause().getMessage(), request);
                                }
                            });
                        } else {
                            isResponseSent[0] = true;
                            respondWith(StatusCode.BAD_REQUEST, validationEvent.getMessage(), request);
                        }
                        return Future.succeededFuture();
                    });
                    assert fut != null;
                    return fut;
                }).onFailure((Throwable ex) -> {
                    if (ex instanceof ValidationException && !isResponseSent[0]) {
                        respondWith(StatusCode.BAD_REQUEST, ex.getMessage(), request);
                        return;
                    }
                    log.error("TODO error handling", exceptionFactory.newException(ex));
                    if (!isResponseSent[0]) {
                        respondWith(StatusCode.INTERNAL_SERVER_ERROR, ex.getMessage(), request);
                    }
                });
            });
            return true;
        }
        return false;
    }

    @Override
    public void resourceChanged(String resourceUri, Buffer resource) {
        if (configResourceUri() != null && configResourceUri().equals(resourceUri)) {
            log.info("Kafka configuration resource {} was updated. Going to initialize with new configuration", resourceUri);
            initializeKafkaConfiguration(resource);
        }
    }

    @Override
    public void resourceRemoved(String resourceUri) {
        if (configResourceUri() != null && configResourceUri().equals(resourceUri)) {
            log.info("Kafka configuration resource {} was removed. Going to close all kafka producers", resourceUri);
            repository.closeAll().future().onComplete(event -> initialized = false);
        }
    }

    private Future<ValidationResult> maybeValidate(HttpServerRequest request, List<KafkaProducerRecord<String, String>> kafkaProducerRecords) {
        if(kafkaMessageValidator != null) {
            var fut = kafkaMessageValidator.validateMessages(request, kafkaProducerRecords);
            assert fut != null;
            return fut;
        }
        var fut = Future.succeededFuture(new ValidationResult(ValidationStatus.VALIDATED_POSITIV));
        assert fut != null;
        return fut;
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

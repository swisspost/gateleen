package org.swisspush.gateleen.kafka;

import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;

import java.util.Map;

import static org.slf4j.LoggerFactory.getLogger;
import static org.swisspush.gateleen.core.exception.GateleenExceptionFactory.newGateleenThriftyExceptionFactory;

public class KafkaHandlerBuilder {

    private static final Logger log = getLogger(KafkaHandlerBuilder.class);
    private Vertx vertx;
    private GateleenExceptionFactory exceptionFactory;
    private ConfigurationResourceManager configurationResourceManager;
    private KafkaMessageValidator kafkaMessageValidator;
    private KafkaProducerRepository repository;
    private KafkaMessageSender kafkaMessageSender;
    private String configResourceUri;
    private String streamingPath;
    private Map<String, Object> properties;

    /** Use {@link KafkaHandler#builder()} */
    KafkaHandlerBuilder() {/**/}

    public KafkaHandler build() {
        if (vertx == null) throw new NullPointerException("vertx missing");
        if (exceptionFactory == null) exceptionFactory = newGateleenThriftyExceptionFactory();
        if (repository == null) throw new NullPointerException("kafkaProducerRepository missing");
        if (kafkaMessageSender == null) throw new NullPointerException("kafkaMessageSender missing");
        if (streamingPath == null) log.warn("no 'streamingPath' given. Are you sure you want none?");
        return new KafkaHandler(
            vertx, exceptionFactory, configurationResourceManager, kafkaMessageValidator, repository,
            kafkaMessageSender, configResourceUri, streamingPath, properties);
    }

    public KafkaHandlerBuilder withVertx(Vertx vertx) {
        this.vertx = vertx;
        return this;
    }

    public KafkaHandlerBuilder withExceptionFactory(GateleenExceptionFactory exceptionFactory) {
        this.exceptionFactory = exceptionFactory;
        return this;
    }

    public KafkaHandlerBuilder withConfigurationResourceManager(ConfigurationResourceManager configurationResourceManager) {
        this.configurationResourceManager = configurationResourceManager;
        return this;
    }

    public KafkaHandlerBuilder withKafkaMessageValidator(KafkaMessageValidator kafkaMessageValidator) {
        this.kafkaMessageValidator = kafkaMessageValidator;
        return this;
    }

    public KafkaHandlerBuilder withRepository(KafkaProducerRepository repository) {
        this.repository = repository;
        return this;
    }

    public KafkaHandlerBuilder withKafkaMessageSender(KafkaMessageSender kafkaMessageSender) {
        this.kafkaMessageSender = kafkaMessageSender;
        return this;
    }

    public KafkaHandlerBuilder withConfigResourceUri(String configResourceUri) {
        this.configResourceUri = configResourceUri;
        return this;
    }

    public KafkaHandlerBuilder withStreamingPath(String streamingPath) {
        this.streamingPath = streamingPath;
        return this;
    }

    public KafkaHandlerBuilder withProperties(Map<String, Object> properties) {
        this.properties = properties;
        return this;
    }

}

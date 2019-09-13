package org.swisspush.gateleen.kafka;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceConsumer;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;

public class KafkaHandler extends ConfigurationResourceConsumer {

    private final Logger log = LoggerFactory.getLogger(KafkaHandler.class);

    private final String streamingPath;

    public KafkaHandler(ConfigurationResourceManager configurationResourceManager, String configResourceUri, String streamingPath) {
        super(configurationResourceManager, configResourceUri, "gateleen_kafka_topic_configuration_schema");
        this.streamingPath = streamingPath;
    }

    public boolean handle(final HttpServerRequest request) {
        final Logger requestLog = RequestLoggerFactory.getLogger(KafkaHandler.class, request);

        if (request.uri().startsWith(streamingPath)) {
            requestLog.debug("Handling {}", request.uri());

            if(HttpMethod.POST != request.method()){
                ResponseStatusCodeLogUtil.info(request, StatusCode.METHOD_NOT_ALLOWED, KafkaHandler.class);
                request.response().setStatusCode(StatusCode.METHOD_NOT_ALLOWED.getStatusCode());
                request.response().setStatusMessage(StatusCode.METHOD_NOT_ALLOWED.getStatusMessage());
                request.response().end();
                return true;
            }

            //TODO implement forwarding to kafka
            ResponseStatusCodeLogUtil.info(request, StatusCode.OK, KafkaHandler.class);
            request.response().setStatusCode(StatusCode.OK.getStatusCode());
            request.response().setStatusMessage(StatusCode.OK.getStatusMessage());
            request.response().end();

            return true;
        }
        return false;
    }

    @Override
    public void resourceChanged(String resourceUri, String resource) {
        if(configResourceUri() != null && configResourceUri().equals(resourceUri)) {
            log.info("*** Got notified about configuration resource update for " + resourceUri + " with new data: " + resource);
        }
    }

    @Override
    public void resourceRemoved(String resourceUri) {
        if(configResourceUri() != null && configResourceUri().equals(resourceUri)){
            log.info("*** Configuration resource "+resourceUri+" was removed. Using default values instead");
        }
    }
}

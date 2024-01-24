package org.swisspush.gateleen.queue.queuing.splitter;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceConsumer;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@inheritDoc}
 */
public class QueueSplitterImpl extends ConfigurationResourceConsumer implements QueueSplitter {

    private final Logger log = LoggerFactory.getLogger(QueueSplitterImpl.class);

    private final Map<String, Object> properties;

    private boolean initialized = false;

    public QueueSplitterImpl(
            ConfigurationResourceManager configurationResourceManager,
            String configResourceUri
    ) {
        this(configurationResourceManager, configResourceUri, new HashMap<>());
    }

    public QueueSplitterImpl(
            ConfigurationResourceManager configurationResourceManager,
            String configResourceUri,
            Map<String, Object> properties
    ) {
        super(configurationResourceManager, configResourceUri, "gateleen_queue_splitter_configuration_schema");
        this.properties = properties;
    }

    public Future<Void> initialize() {
        Promise<Void> promise = Promise.promise();
        configurationResourceManager().getRegisteredResource(configResourceUri()).onComplete((event -> {
            if (event.succeeded() && event.result().isPresent()) {
                initializeQueueSplitterConfiguration(event.result().get()).onComplete((event1 -> promise.complete()));
            } else {
                log.warn("No queue splitter configuration resource with uri '{}' found. Unable to setup kafka configuration correctly", configResourceUri());
                promise.complete();
            }
        }));
        return promise.future();
    }

    public boolean isInitialized() {
        return initialized;
    }

    private Future<Void> initializeQueueSplitterConfiguration(Buffer configuration) {
        Promise<Void> promise = Promise.promise();
        final List<QueueSplitterConfiguration> kafkaConfigurations = QueueSplitterConfigurationParser.parse(configuration, properties);

        // TODO: release all splitters and creates new ones

        return promise.future();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String convertToSubQueue(final String queue, HttpServerRequest request) {
        return queue + "-1";
    }

    @Override
    public void resourceChanged(String resourceUri, Buffer resource) {
        if (configResourceUri() != null && configResourceUri().equals(resourceUri)) {
            log.info("Queue splitter configuration resource {} was updated. Going to initialize with new configuration", resourceUri);
            initializeQueueSplitterConfiguration(resource);
        }
    }

    @Override
    public void resourceRemoved(String resourceUri) {
        if (configResourceUri() != null && configResourceUri().equals(resourceUri)) {
            log.info("Queue splitter configuration resource {} was removed. Going to close all kafka producers", resourceUri);

            // TODO: release all splitters and creates new ones

            initialized = false;
        }
    }
}

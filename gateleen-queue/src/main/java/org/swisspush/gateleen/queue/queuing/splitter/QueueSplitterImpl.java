package org.swisspush.gateleen.queue.queuing.splitter;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceConsumer;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;
import org.swisspush.gateleen.queue.queuing.splitter.executors.QueueSplitExecutor;
import org.swisspush.gateleen.queue.queuing.splitter.executors.QueueSplitExecutorFromList;
import org.swisspush.gateleen.queue.queuing.splitter.executors.QueueSplitExecutorFromRequest;

import java.util.*;
import java.util.stream.Collectors;

/**
 * {@inheritDoc}
 */
public class QueueSplitterImpl extends ConfigurationResourceConsumer implements QueueSplitter {

    private final Logger log = LoggerFactory.getLogger(QueueSplitterImpl.class);

    private final Map<String, Object> properties;

    private boolean initialized = false;

    private List<QueueSplitExecutor> queueSplitExecutors = new ArrayList<>();

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
                initializeQueueSplitterConfiguration(event.result().get());
                promise.complete();
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

    private void initializeQueueSplitterConfiguration(Buffer configuration) {
        final List<QueueSplitterConfiguration> configurations = QueueSplitterConfigurationParser.parse(configuration, properties);
        queueSplitExecutors.clear();
        queueSplitExecutors = configurations.stream().map(queueSplitterConfiguration -> {
            if (queueSplitterConfiguration.isSplitStatic()) {
                return new QueueSplitExecutorFromList(queueSplitterConfiguration);
            } else {
                return new QueueSplitExecutorFromRequest(queueSplitterConfiguration);
            }
        }).collect(Collectors.toList());
        initialized = true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String convertToSubQueue(final String queue, HttpServerRequest request) {
        Optional<QueueSplitExecutor> executor = queueSplitExecutors.stream().filter(splitExecutor -> splitExecutor.matches(queue)).findFirst();
        return executor.isPresent() ? executor.get().executeSplit(queue, request) : queue;
    }

    @Override
    public void resourceChanged(String resourceUri, Buffer resource) {
        if (configResourceUri() != null && configResourceUri().equals(resourceUri)) {
            log.info("Queue splitter configuration resource {} was updated. Going to initialize with new configuration", resourceUri);
            System.out.println("Queue splitter configuration resource changed");
            initializeQueueSplitterConfiguration(resource);
        }
    }

    @Override
    public void resourceRemoved(String resourceUri) {
        if (configResourceUri() != null && configResourceUri().equals(resourceUri)) {
            log.info("Queue splitter configuration resource {} was removed. Going to close all kafka producers", resourceUri);
            System.out.println("Queue splitter configuration resource removed");
            queueSplitExecutors.clear();
            initialized = false;
        }
    }
}

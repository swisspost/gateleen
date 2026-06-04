package org.swisspush.gateleen.queue.queuing.splitter;

import com.google.common.base.Strings;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceConsumer;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;
import org.swisspush.gateleen.queue.queuing.splitter.executors.QueueSplitExecutor;
import org.swisspush.gateleen.queue.queuing.splitter.executors.QueueSplitExecutorFromRequest;
import org.swisspush.gateleen.queue.queuing.splitter.executors.QueueSplitExecutorFromStaticList;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.swisspush.gateleen.queue.queuing.splitter.QueueSplitterConfigurationParser.DEFAULT_POSTFIX_DELIMITER;

public class QueueSplitterImpl extends ConfigurationResourceConsumer implements QueueSplitter {

    public static final String NUMBER_OF_STATIC_QUEUES = "x-static-queue-count";
    private final Logger log = LoggerFactory.getLogger(QueueSplitterImpl.class);

    private final Map<String, Object> properties;

    private List<QueueSplitExecutor> configurableQueueSplitExecutors = new ArrayList<>();
    private Map<String, QueueSplitExecutorFromStaticList> dynamicQueueSplitExecutors = new HashMap<>();

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
                log.warn("No queue splitter configuration resource with uri '{}' found. Unable to setup splitter configuration correctly", configResourceUri());
                promise.complete();
            }
        }));
        return promise.future();
    }

    private void initializeQueueSplitterConfiguration(Buffer configuration) {
        final List<QueueSplitterConfiguration> configurations = QueueSplitterConfigurationParser.parse(configuration, properties);
        configurableQueueSplitExecutors.clear();
        configurableQueueSplitExecutors = configurations.stream().map(queueSplitterConfiguration -> {
            if (queueSplitterConfiguration.isSplitStatic()) {
                return new QueueSplitExecutorFromStaticList(queueSplitterConfiguration);
            } else {
                return new QueueSplitExecutorFromRequest(queueSplitterConfiguration);
            }
        }).collect(Collectors.toList());
    }

    /**
     * check queue request do we need do split, which created by header
     *
     * @param queueName
     * @param request
     * @return
     */
    private String dynamicSplitProcessing(String queueName, HttpServerRequest request) {

        final String numberOfQueueString = request.headers() == null ? null : request.headers().get(NUMBER_OF_STATIC_QUEUES);
        if (Strings.isNullOrEmpty(numberOfQueueString)) {
            // do nothing
            return queueName;
        }

        int numberOfQueue;
        try {
            numberOfQueue = Integer.parseInt(numberOfQueueString);
        } catch (NumberFormatException ex) {
            log.error("can not parsing number of queue from {}", numberOfQueueString, log.isDebugEnabled() ? ex : null);
            return queueName;
        }

        // split a queue into ONE group, there is meaningless
        if (numberOfQueue <= 1) {
            log.warn("number of queue {} is less than 2, queue split will not enable, exist queue split will disable", queueName);
            dynamicQueueSplitExecutors.remove(queueName);
            return queueName;
        }

        QueueSplitExecutorFromStaticList dynamicQueueSplitExecutor = null;
        // do we have existed split executor, create if missing
        if (dynamicQueueSplitExecutors.containsKey(queueName)) {
            QueueSplitExecutorFromStaticList existDynamicQueueSplitExecutor = dynamicQueueSplitExecutors.get(queueName);
            int existConfigSize = existDynamicQueueSplitExecutor.getConfiguration().getPostfixFromStatic() == null ? 0 : existDynamicQueueSplitExecutor.getConfiguration().getPostfixFromStatic().size();
            if (existConfigSize != numberOfQueue) {
                // queue split config changed, will update
                log.debug("{} already exists in queue, will update, number of queue change from {} to {}", queueName, existConfigSize, numberOfQueue);
                existDynamicQueueSplitExecutor = createDynamicQueueSplitExecutor(queueName, numberOfQueue);
                dynamicQueueSplitExecutors.put(queueName, existDynamicQueueSplitExecutor);
            }
            dynamicQueueSplitExecutor = existDynamicQueueSplitExecutor;
        } else {
            log.info("create new queue split executor for queue '{}'", queueName);
            dynamicQueueSplitExecutor = createDynamicQueueSplitExecutor(queueName, numberOfQueue);
            dynamicQueueSplitExecutors.put(queueName, dynamicQueueSplitExecutor);
        }

        return dynamicQueueSplitExecutor.executeSplit(queueName, request);
    }

    private QueueSplitExecutorFromStaticList createDynamicQueueSplitExecutor(String queueName, int numberOfQueue) {
        List<String> staticPostfixes = IntStream.rangeClosed(1, numberOfQueue)
                .mapToObj(i -> "Q" + i)
                .collect(Collectors.toList());
        QueueSplitterConfiguration queueSplitterConfiguration = new QueueSplitterConfiguration(
                Pattern.compile(queueName),
                DEFAULT_POSTFIX_DELIMITER,
                staticPostfixes,
                null,
                null);
        return new QueueSplitExecutorFromStaticList(queueSplitterConfiguration);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String convertToSubQueue(final String queue, HttpServerRequest request) {
        Optional<QueueSplitExecutor> executor = configurableQueueSplitExecutors.stream().filter(splitExecutor -> splitExecutor.matches(queue)).findFirst();
        if (executor.isPresent()) {
            return executor.get().executeSplit(queue, request);
        }
        return dynamicSplitProcessing(queue, request);
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
            log.info("Queue splitter configuration resource {} was removed. Going to release all executors", resourceUri);
            configurableQueueSplitExecutors.clear();
        }
    }
}

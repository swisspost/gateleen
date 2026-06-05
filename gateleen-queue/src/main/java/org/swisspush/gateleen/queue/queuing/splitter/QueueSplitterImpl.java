package org.swisspush.gateleen.queue.queuing.splitter;

import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
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
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.swisspush.gateleen.queue.queuing.splitter.QueueSplitterConfigurationParser.DEFAULT_POSTFIX_DELIMITER;

public class QueueSplitterImpl extends ConfigurationResourceConsumer implements QueueSplitter {

    public static final String NUMBER_OF_STATIC_QUEUES = "x-static-queue-count";
    public static final int DYNAMIC_QUEUES_EXPIRE_TIME_HOURS = 24;
    private final Logger log = LoggerFactory.getLogger(QueueSplitterImpl.class);

    private final Map<String, Object> properties;
    private List<QueueSplitExecutor> configurableQueueSplitExecutors = new ArrayList<>();
    private final Cache<String, QueueSplitExecutorFromStaticList> dynamicQueueSplitExecutors;

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
        dynamicQueueSplitExecutors = CacheBuilder.newBuilder()
                .expireAfterAccess(DYNAMIC_QUEUES_EXPIRE_TIME_HOURS, TimeUnit.HOURS)
                .build();

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
        final QueueSplitExecutorFromStaticList existDynamicQueueSplitExecutor = dynamicQueueSplitExecutors.getIfPresent(queueName);

        if (Strings.isNullOrEmpty(numberOfQueueString) && existDynamicQueueSplitExecutor == null) {
            // do nothing
            return queueName;
        }

        // take the numberOfQueue from exist executor, if there is one.
        int numberOfQueue = existDynamicQueueSplitExecutor == null ? 0 : existDynamicQueueSplitExecutor.getConfiguration().getPostfixFromStatic().size();
        try {
            numberOfQueue = Integer.parseInt(numberOfQueueString);
        } catch (NumberFormatException ex) {
            log.error("can not parsing number of queue from {}", numberOfQueueString, log.isDebugEnabled() ? ex : null);
            if (existDynamicQueueSplitExecutor == null) {
                return queueName;
            }
        }

        // split a queue into ONE group, there is meaningless
        if (numberOfQueue <= 1) {
            log.warn("number of queue {} is less than 2, queue split will not enable, exist queue split will disable", queueName);
            dynamicQueueSplitExecutors.invalidate(queueName);
            return queueName;
        }

        QueueSplitExecutorFromStaticList dynamicQueueSplitExecutor;
        // do we have existed split executor, create if missing
        if (existDynamicQueueSplitExecutor != null) {
            int existConfigSize = existDynamicQueueSplitExecutor.getConfiguration().getPostfixFromStatic() == null ? 0 : existDynamicQueueSplitExecutor.getConfiguration().getPostfixFromStatic().size();
            dynamicQueueSplitExecutor = existDynamicQueueSplitExecutor;
            if (existConfigSize != numberOfQueue) {
                // queue split config changed, will update
                log.debug("{} already exists in queue, will update, number of queue change from {} to {}", queueName, existConfigSize, numberOfQueue);
                dynamicQueueSplitExecutor = createDynamicQueueSplitExecutor(queueName, numberOfQueue);
                dynamicQueueSplitExecutors.put(queueName, dynamicQueueSplitExecutor);
            }
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
        dynamicQueueSplitExecutors.invalidateAll();
    }
}

package org.swisspush.gateleen.monitoring;

import com.google.common.collect.Ordering;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.impl.RedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.HttpServerRequestUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.core.util.StringUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.swisspush.redisques.util.RedisquesAPI.*;

/**
 * Handler to monitor the server state using the Metrics library. The recorded informations are accessible through JMX MBeans.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class MonitoringHandler {

    public static final String METRIC_NAME = "name";
    public static final String METRIC_ACTION = "action";
    public static final String MARK = "mark";
    public static final String SET = "set";

    /**
     * Action used for a message that carries multiple merged metrics at once (see {@link #METRICS_BATCH_ITEMS}).
     * Used only when the {@code mergeMetricsIntoSingleMessage} constructor parameter is enabled.
     */
    public static final String BATCH = "batch";

    /**
     * Key holding the {@link JsonArray} of individual metric {@link JsonObject}s (each with the usual
     * {@link #METRIC_NAME}/{@link #METRIC_ACTION}/"n" fields) inside a {@link #BATCH} message.
     */
    public static final String METRICS_BATCH_ITEMS = "metrics";

    private Vertx vertx;
    private ResourceStorage storage;

    private boolean requestPerRuleMonitoringActive;
    private String requestPerRuleMonitoringProperty;
    private final String requestPerRuleMonitoringPath;
    private Map<String, Long> requestPerRuleMonitoringMap;

    private static Logger log = LoggerFactory.getLogger(MonitoringHandler.class);
    private final Logger metricLogger = LoggerFactory.getLogger("Metrics");
    private final Map<String, Long> metricCache = new HashMap<>();
    private final Map<String, Long> lastDumps = new HashMap<>();

    public static final String REQUESTS_CLIENT_NAME = "requests.localhost";
    public static final String REQUESTS_BACKENDS_NAME = "requests.backends";
    private static final String REQUESTS_INCOMING_NAME = "requests.incoming";

    public static final String PENDING_REQUESTS_METRIC = "requests.pending.count";
    public static final String ACTIVE_QUEUE_COUNT_METRIC = "queues.active.count";
    public static final String LAST_USED_QUEUE_SIZE_METRIC = "queues.last.size";

    public static final String ENQUEUE_METRIC = "queues.enqueue";
    public static final String DEQUEUE_METRIC = "queues.dequeue";

    public static final String LISTENER_COUNT_METRIC = "hooks.listener.count";
    public static final String ROUTE_COUNT_METRIC = "hooks.route.count";

    @Deprecated
    public static final String QUEUES_KEY_PREFIX = "redisques:queues";
    @Deprecated
    public static final int MAX_AGE_MILLISECONDS = 120000; // 120 seconds

    private static final int NON_TIME_SENSITIVE_METRICS_PUBLISH_TIME = 5000; // 5 seconds

    public static final String REQUEST_PER_RULE_PREFIX = "rpr.";
    public static final String REQUEST_PER_RULE_PROPERTY = "org.swisspush.request.rule.property";
    public static final String REQUEST_PER_RULE_SAMPLING_PROPERTY = "org.swisspush.request.rule.sampling";
    public static final String REQUEST_PER_RULE_EXPIRY_PROPERTY = "org.swisspush.request.rule.expiry";
    public static final long REQUEST_PER_RULE_DEFAULT_SAMPLING = 60000; // 60 seconds
    public static final long REQUEST_PER_RULE_DEFAULT_EXPIRY = 86400; // 24 hours
    private final String UNKNOWN_VALUE = "unknown";
    private final String EXPIRE_AFTER_HEADER = "x-expire-after";

    private String prefix;
    private long requestPerRuleSampling;
    private long requestPerRuleExpiry;
    private final UUID uuid;
    private final List<Handler<Message<JsonObject>>> receivers = new ArrayList<>();

    // Local buffers for non-time-sensitive metrics. Instead of sending an eventbus message on every
    // single update, the value is accumulated/cached locally and flushed periodically (see
    // registerQueueSizeTrackingTimer) - only when the value actually changed - to reduce eventbus load.
    private final AtomicLong pendingRequestCount = new AtomicLong(0);
    // starts at the same value as pendingRequestCount (0) so that an initial flush without any prior
    // startRequestMetricTracking/stopRequestMetricTracking call does not needlessly send a "0" value
    private final AtomicLong lastSentPendingRequestCount = new AtomicLong(0);
    private final AtomicReference<String> pendingLastUsedQueueName = new AtomicReference<>();
    private final AtomicLong lastSentLastUsedQueueSize = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong listenerCount = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong lastSentListenerCount = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong routeCount = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong lastSentRouteCount = new AtomicLong(Long.MIN_VALUE);

    // When enabled, the non-time-sensitive metrics flushed together in a single flush cycle (see
    // flushBufferedMetrics) are combined into a single eventbus message (action == BATCH) instead of being sent
    // as one message per metric. This further reduces the eventbus load. Disabled by default to preserve the
    // existing wire format for consumers listening on the monitoring address. Configurable via constructor.
    private final boolean mergeMetricsIntoSingleMessage;

    public interface MonitoringCallback {

        void onDone(JsonObject result);

        void onFail(String errorMessage, int statusCode);
    }

    private interface QueueLengthCollectingCallback {
        void onDone(List<Map.Entry<String, Long>> mapEntries);
    }

    /**
     * Constructor
     * @deprecated use {@link #MonitoringHandler(Vertx, ResourceStorage, String)} instead
     */
    @Deprecated
    public MonitoringHandler(Vertx vertx, RedisClient redisClient, final ResourceStorage storage, String prefix) {
        this(vertx, storage, prefix);
        log.warn("Deprecated constructor used. This constructor should not be used anymore since it may be removed in future releases.");
    }

    /**
     * Constructor
     * @deprecated use {@link #MonitoringHandler(Vertx, ResourceStorage, String, String)} instead
     */
    @Deprecated
    public MonitoringHandler(Vertx vertx, RedisClient redisClient, final ResourceStorage storage, String prefix, String requestPerRulePath) {
        this(vertx, storage, prefix, requestPerRulePath);
        log.warn("Deprecated constructor used. This constructor should not be used anymore since it may be removed in future releases.");
    }

    public MonitoringHandler(Vertx vertx, final ResourceStorage storage, String prefix) {
        this(vertx, storage, prefix, null);
    }

    public MonitoringHandler(Vertx vertx, final ResourceStorage storage, String prefix, String requestPerRulePath) {
        this(vertx, storage, prefix, requestPerRulePath, false);
    }

    /**
     * Constructor
     *
     * @param vertx vertx
     * @param storage the resource storage
     * @param prefix the prefix used for all metric names
     * @param requestPerRulePath the storage path used for the request per rule monitoring feature
     * @param mergeMetricsIntoSingleMessage when {@code true}, the non-time-sensitive metrics (pending request
     *                                      count, last used queue size, listener count, route count) flushed
     *                                      together in a single flush cycle are combined into a single eventbus
     *                                      message (action == {@link #BATCH}) instead of being sent as one
     *                                      message per metric, further reducing the load on the eventbus.
     *                                      Defaults to {@code false} when using a constructor without this
     *                                      parameter.
     */
    public MonitoringHandler(Vertx vertx, final ResourceStorage storage, String prefix, String requestPerRulePath,
                              boolean mergeMetricsIntoSingleMessage) {
        this.vertx = vertx;
        this.storage = storage;
        this.prefix = prefix;
        this.requestPerRuleMonitoringPath = initRequestPerRuleMonitoringPath(requestPerRulePath);
        this.uuid = UUID.randomUUID();
        this.mergeMetricsIntoSingleMessage = mergeMetricsIntoSingleMessage;

        registerQueueSizeTrackingTimer();

        initRequestPerRuleMonitoring();

        vertx.eventBus().consumer(getMonitoringAddress(), (Handler<Message<JsonObject>>) message -> {
            for (Handler<Message<JsonObject>> receiver : receivers) {
                receiver.handle(message);
            }
            final JsonObject body = message.body();
            if (BATCH.equals(body.getString(METRIC_ACTION))) {
                JsonArray items = body.getJsonArray(METRICS_BATCH_ITEMS);
                if (items != null) {
                    for (int i = 0; i < items.size(); i++) {
                        handleMetricMessage(items.getJsonObject(i));
                    }
                }
            } else {
                handleMetricMessage(body);
            }
        });
    }

    /**
     * Processes a single metric message (name/action/n), updating the internal metric cache used to decide when
     * a value change should be logged, and forwarding rule-per-request metrics to storage.
     *
     * @param body the metric message, either received directly or extracted from a {@link #BATCH} message
     */
    private void handleMetricMessage(JsonObject body) {
        final String action = body.getString(METRIC_ACTION);
        final String name = body.getString(METRIC_NAME);
        handleRequestPerRuleMessage(name);
        long now;
        switch (action) {
            case "set":
            case "update":
                Long currentValue = metricCache.get(name);
                Long newValue = body.getLong("n");
                Long lastDump = lastDumps.get(name);
                now = System.currentTimeMillis() / 1000;
                if (!newValue.equals(currentValue) || lastDump != null && lastDump < now - 300) {
                    metricLogger.info(name + " " + body.getLong("n") + " " + now);
                    metricCache.put(name, newValue);
                    lastDumps.put(name, now);
                }
                break;
        }
    }

    /**
     * Register an external receiver interested in monitoring data.
     * @param receiver a handler
     */
    public void registerReceiver(Handler<Message<JsonObject>> receiver) {
        receivers.add(receiver);
    }

    /**
     * Get the event bus address of the monitoring.
     * Override this method when you want to use a custom monitoring address
     *
     * @return the event bus address of monitoring
     */
    protected String getMonitoringAddress(){
        return Address.monitoringAddress();
    }

    /**
     * Get the event bus address of redisques.
     * Override this method when you want to use a custom redisques address
     *
     * @return the event bus address of redisques
     */
    protected String getRedisquesAddress(){
        return Address.redisquesAddress();
    }

    public String getRequestPerRuleMonitoringPath() {
        return requestPerRuleMonitoringPath;
    }

    private String initRequestPerRuleMonitoringPath(String requestPerRuleMonitoringPath){
        String str = StringUtils.trim(requestPerRuleMonitoringPath);
        if(StringUtils.isNotEmpty(str) && str.endsWith("/")){
            str = str.substring(0, str.length()-1);
        }
        return str;
    }

    private void handleRequestPerRuleMessage(String metricName){
        if(StringUtils.isNotEmpty(metricName) && metricName.startsWith(prefix + REQUEST_PER_RULE_PREFIX)){
            writeRequestPerRuleMonitoringMetricsToStorage(metricName.replaceAll(prefix+REQUEST_PER_RULE_PREFIX, ""));
        }
    }

    private void initRequestPerRuleMonitoring(){
        requestPerRuleMonitoringProperty = StringUtils.getStringOrEmpty(System.getProperty(REQUEST_PER_RULE_PROPERTY));
        if(StringUtils.isNotEmpty(requestPerRuleMonitoringProperty)){
            requestPerRuleMonitoringActive = true;
            log.info("Activated request per rule monitoring for request header property '{}'", requestPerRuleMonitoringProperty);
            configureSamplingAndExpiry();
            registerRequestPerRuleMonitoringTimer();
        } else {
            requestPerRuleMonitoringActive = false;
            log.info("Request per rule monitoring not active since system property '{}' was not set (or empty)", REQUEST_PER_RULE_PROPERTY);
        }
    }

    public boolean isRequestPerRuleMonitoringActive() {
        return requestPerRuleMonitoringActive;
    }

    /**
     * Returns whether the non-time-sensitive metrics (pending request count, last used queue size, listener
     * count, route count) flushed in a single flush cycle are merged into one combined eventbus message
     * (action == {@link #BATCH}) instead of being sent as one message per metric. Configured via constructor.
     */
    public boolean isMergeMetricsIntoSingleMessage() {
        return mergeMetricsIntoSingleMessage;
    }

    private Map<String, Long> getRequestPerRuleMonitoringMap() {
        if(requestPerRuleMonitoringMap == null){
            requestPerRuleMonitoringMap = new HashMap<>();
        }
        return requestPerRuleMonitoringMap;
    }

    private void registerQueueSizeTrackingTimer() {
        vertx.setPeriodic(NON_TIME_SENSITIVE_METRICS_PUBLISH_TIME, event -> {
            updateQueueCountInformation();
            flushBufferedMetrics();
        });
    }

    /**
     * Flushes locally buffered non-time-sensitive metrics (pending request count, last used queue size,
     * listener count, route count) to the eventbus. Values are only sent when they actually changed since
     * the last flush, in order to minimize the load on the eventbus.
     * <p>
     * When {@link #isMergeMetricsIntoSingleMessage()} is enabled, all the metrics of this flush cycle that
     * actually changed are combined and sent as a single {@link #BATCH} message instead of one message per
     * metric, further reducing the number of eventbus sends.
     */
    void flushBufferedMetrics() {
        final JsonArray mergedMetrics = mergeMetricsIntoSingleMessage ? new JsonArray() : null;

        flushPendingRequestCount(mergedMetrics);
        flushListenerCount(mergedMetrics);
        flushRouteCount(mergedMetrics);

        // last used queue size requires an async roundtrip to redisques, so it is flushed last. Once it
        // completes (or is skipped because there is nothing to flush), the merged batch (if any) is sent.
        flushLastUsedQueueSize(mergedMetrics, () -> {
            if (mergedMetrics != null && !mergedMetrics.isEmpty()) {
                vertx.eventBus().send(getMonitoringAddress(),
                        new JsonObject().put(METRIC_ACTION, BATCH).put(METRICS_BATCH_ITEMS, mergedMetrics));
            }
        });
    }

    /**
     * Either adds the given metric to the mergedMetrics array (when merging is enabled, i.e. mergedMetrics is
     * not null) or sends it immediately as its own eventbus message (when merging is disabled).
     */
    private void collectOrSendMetric(JsonArray mergedMetrics, String metricName, long value) {
        JsonObject metric = new JsonObject().put(METRIC_NAME, prefix + metricName).put(METRIC_ACTION, SET).put("n", value);
        if (mergedMetrics != null) {
            mergedMetrics.add(metric);
        } else {
            vertx.eventBus().send(getMonitoringAddress(), metric);
        }
    }

    private void flushPendingRequestCount(JsonArray mergedMetrics) {
        long current = pendingRequestCount.get();
        if (current != lastSentPendingRequestCount.getAndSet(current)) {
            collectOrSendMetric(mergedMetrics, PENDING_REQUESTS_METRIC, current);
        }
    }

    private void flushLastUsedQueueSize(JsonArray mergedMetrics, Runnable onComplete) {
        final String queue = pendingLastUsedQueueName.getAndSet(null);
        if (queue == null) {
            onComplete.run();
            return;
        }
        vertx.eventBus().request(getRedisquesAddress(), buildGetQueueItemsCountOperation(queue), (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
            if (reply.succeeded() && OK.equals(reply.result().body().getString(STATUS))) {
                final long count = reply.result().body().getLong(VALUE);
                if (count != lastSentLastUsedQueueSize.getAndSet(count)) {
                    collectOrSendMetric(mergedMetrics, LAST_USED_QUEUE_SIZE_METRIC, count);
                }
            } else {
                log.error("Error gathering queue size for queue '{}'", queue);
            }
            onComplete.run();
        });
    }

    private void flushListenerCount(JsonArray mergedMetrics) {
        long current = listenerCount.get();
        if (current != Long.MIN_VALUE && current != lastSentListenerCount.getAndSet(current)) {
            collectOrSendMetric(mergedMetrics, LISTENER_COUNT_METRIC, current);
        }
    }

    private void flushRouteCount(JsonArray mergedMetrics) {
        long current = routeCount.get();
        if (current != Long.MIN_VALUE && current != lastSentRouteCount.getAndSet(current)) {
            collectOrSendMetric(mergedMetrics, ROUTE_COUNT_METRIC, current);
        }
    }

    private void registerRequestPerRuleMonitoringTimer(){
        vertx.setPeriodic(requestPerRuleSampling, event -> submitRequestPerRuleMonitoringMetrics());
    }

    private void configureSamplingAndExpiry(){
        String sampling = System.getProperty(REQUEST_PER_RULE_SAMPLING_PROPERTY, String.valueOf(REQUEST_PER_RULE_DEFAULT_SAMPLING));
        String expiry = System.getProperty(REQUEST_PER_RULE_EXPIRY_PROPERTY, String.valueOf(REQUEST_PER_RULE_DEFAULT_EXPIRY));

        try {
            this.requestPerRuleSampling = Long.parseLong(sampling);
            log.info("Initializing request per rule monitoring with a sampling rate of [ms] {}", requestPerRuleSampling);
        } catch (NumberFormatException ex){
            log.warn("Unable to parse system property '{}'. Using default value instead: {}",
                    REQUEST_PER_RULE_SAMPLING_PROPERTY, REQUEST_PER_RULE_DEFAULT_SAMPLING);
            this.requestPerRuleSampling = REQUEST_PER_RULE_DEFAULT_SAMPLING;
        }

        try {
            this.requestPerRuleExpiry = Long.parseLong(expiry);
            log.info("Initializing request per rule monitoring with an expiry value of [ms] {}", requestPerRuleExpiry);
        } catch (NumberFormatException ex){
            log.warn("Unable to parse system property '{}'. Using default value instead: {}",
                    REQUEST_PER_RULE_EXPIRY_PROPERTY, REQUEST_PER_RULE_DEFAULT_EXPIRY);
            this.requestPerRuleExpiry= REQUEST_PER_RULE_DEFAULT_EXPIRY;
        }
    }

    public long getRequestPerRuleSampling() {
        return requestPerRuleSampling;
    }

    public long getRequestPerRuleExpiry() {
        return requestPerRuleExpiry;
    }

    public void updateIncomingRequests(HttpServerRequest request) {
        if (!HttpServerRequestUtil.isRemoteAddressLoopbackAddress(request) && shouldBeTracked(request.uri())) {
            vertx.eventBus().send(getMonitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + REQUESTS_INCOMING_NAME).put(METRIC_ACTION, MARK));
        }
    }

    public void updateRequestPerRuleMonitoring(HttpServerRequest request, String metricName){
        if(isRequestPerRuleMonitoringActive()){
            String headerValue = StringUtils.getStringOrDefault(request.getHeader(requestPerRuleMonitoringProperty), UNKNOWN_VALUE);
            if(StringUtils.isNotEmptyTrimmed(metricName)){
                String key = headerValue + "." + metricName;
                getRequestPerRuleMonitoringMap().merge(key, 1L, Long::sum);
            } else {
                Logger requestlog = RequestLoggerFactory.getLogger(MonitoringHandler.class, request);
                requestlog.warn("Request per rule monitoring is active but was called without a rule metricName. This request will be ignored.");
            }
        }
    }

    private void submitRequestPerRuleMonitoringMetrics(){
        log.info("About to send {} request per rule monitoring values to metrics", getRequestPerRuleMonitoringMap().size());
        for (Iterator<Map.Entry<String, Long>> it = getRequestPerRuleMonitoringMap().entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Long> entry = it.next();
            vertx.eventBus().send(getMonitoringAddress(),
                    new JsonObject()
                            .put(METRIC_NAME, prefix + REQUEST_PER_RULE_PREFIX + entry.getKey())
                            .put(METRIC_ACTION, SET)
                            .put("n", entry.getValue()));
            it.remove();
        }
    }

    private void writeRequestPerRuleMonitoringMetricsToStorage(String name){
        if(StringUtils.isNotEmptyTrimmed(requestPerRuleMonitoringPath)) {
            String path = requestPerRuleMonitoringPath + "/" + uuid + "/" + name;
            JsonObject obj = new JsonObject().put("timestamp", System.currentTimeMillis());
            MultiMap headers = MultiMap.caseInsensitiveMultiMap().add(EXPIRE_AFTER_HEADER, String.valueOf(requestPerRuleExpiry));
            storage.put(path, headers, Buffer.buffer(obj.encode()), status -> {
                if (status != StatusCode.OK.getStatusCode()) {
                    log.error("Error putting resource {} to storage", path);
                }
            });
        } else {
            log.warn("No path configured for the request per rule monitoring");
        }
    }

    /**
     * Update the meter values for requests. Requests from clients and requests to other backends are measured separately.
     *
     * @param target the target url of the request
     * @param uri uri
     */
    public void updateRequestsMeter(String target, String uri) {
        if (shouldBeTracked(uri)) {
            if (isRequestToExternalTarget(target)) {
                vertx.eventBus().send(getMonitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + REQUESTS_BACKENDS_NAME).put(METRIC_ACTION, MARK));
            } else {
                vertx.eventBus().send(getMonitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + REQUESTS_CLIENT_NAME).put(METRIC_ACTION, MARK));
            }
        }
    }

    /**
     * Start the metric tracking for the requests defined with the metricName (routing rule)
     *
     * @param metricName the name of the metric. This name will be used as name of the JMX MBean. metricNames are defined in routing rules with the property "metricName"
     * @param targetUri targetUri
     * @return long
     */
    public long startRequestMetricTracking(final String metricName, String targetUri) {
        long time = 0;
        if (shouldBeTracked(targetUri)) {
            if (metricName != null) {
                time = System.nanoTime();
                vertx.eventBus().send(getMonitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + "routing." + metricName).put(METRIC_ACTION, MARK));
            }
            updatePendingRequestCount(true);
        }
        return time;
    }

    /**
     * Stop the metric tracking for the requests defined with the metricName (routing rule)
     *
     * @param metricName the name of the metric. This name will be used as name of the JMX MBean. metricNames are defined in routing rules with the property "metricName"
     * @param startTime start time
     * @param targetUri target uri
     */
    public void stopRequestMetricTracking(final String metricName, long startTime, String targetUri) {
        if (shouldBeTracked(targetUri)) {
            if (metricName != null) {
                double duration = (System.nanoTime() - startTime) / 1000000d;
                vertx.eventBus().send(getMonitoringAddress(),
                        new JsonObject().put(METRIC_NAME, prefix + "routing." + metricName + ".duration").put(METRIC_ACTION, "set").put("n", duration));
            }
            updatePendingRequestCount(false);
        }
    }

    private void updatePendingRequestCount(boolean incrementCount) {
        long updated = incrementCount ? pendingRequestCount.incrementAndGet() : pendingRequestCount.decrementAndGet();
        log.trace("Updating count for pending requests: {} remaining (buffered, flushed periodically)", updated);
    }

    /**
     * Update the count of active queues. Reads the count from redis and stores it to JMX.
     */
    public void updateQueueCountInformation() {
        vertx.eventBus().request(getRedisquesAddress(), buildGetQueuesCountOperation(), (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
            if (reply.succeeded() && OK.equals(reply.result().body().getString(STATUS))) {
                final long count = reply.result().body().getLong(VALUE);
                vertx.eventBus().send(getMonitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + ACTIVE_QUEUE_COUNT_METRIC).put(METRIC_ACTION, SET).put("n", count));
            } else {
                log.error("Error gathering count of active queues");
            }
        });
    }

    /**
     * Reads the size from the last used Queue from redis and stores it to JMX
     *
     * @param queue the name of the queue the last update was made
     */
    public void updateLastUsedQueueSizeInformation(final String queue) {
        log.trace("About to update last used Queue size counter (buffered, flushed periodically)");
        pendingLastUsedQueueName.set(queue);
    }

    /**
     * Updates the information about the sizes of the top (numQueues) sized queues.
     *
     * @param numQueues the number of queues
     * @param showEmptyQueues show empty queues or not
     * @param callback the callback returning the result
     */
    public void updateQueuesSizesInformation(final int numQueues, final boolean showEmptyQueues, final MonitoringCallback callback) {
        final JsonObject resultObject = new JsonObject();
        final JsonArray queuesArray = new JsonArray();
        vertx.eventBus().request(getRedisquesAddress(), buildGetQueuesOperation(), (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
            if (reply.succeeded() && OK.equals(reply.result().body().getString(STATUS))) {
                final List<String> queueNames = reply.result().body().getJsonObject(VALUE).getJsonArray("queues").getList();
                collectQueueLengths(queueNames, numQueues, showEmptyQueues, mapEntries -> {
                    for (Map.Entry<String, Long> entry : mapEntries) {
                        JsonObject obj = new JsonObject();
                        obj.put(METRIC_NAME, entry.getKey());
                        obj.put("size", entry.getValue());
                        queuesArray.add(obj);
                    }
                    resultObject.put("queues", queuesArray);
                    callback.onDone(resultObject);
                });
            } else {
                String error = "Error gathering names of active queues";
                log.error(error);
                callback.onFail(error, StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
            }
    });
    }

    private void collectQueueLengths(final List<String> queueNames, final int numOfQueues, final boolean showEmptyQueues, final QueueLengthCollectingCallback callback) {
        final SortedMap<String, Long> resultMap = new TreeMap<>();
        final List<Map.Entry<String, Long>> mapEntryList = new ArrayList<>();
        final AtomicInteger subCommandCount = new AtomicInteger(queueNames.size());
        if (!queueNames.isEmpty()) {
            for (final String name : queueNames) {
                vertx.eventBus().request(getRedisquesAddress(), buildGetQueueItemsCountOperation(name), (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
                    subCommandCount.decrementAndGet();
                    if (reply.succeeded() && OK.equals(reply.result().body().getString(STATUS))) {
                        final long count = reply.result().body().getLong(VALUE);
                        if (showEmptyQueues || count > 0) {
                            resultMap.put(name, count);
                        }
                    } else {
                        log.error("Error gathering size of queue {}", name);
                    }

                    if (subCommandCount.get() == 0) {
                        mapEntryList.addAll(resultMap.entrySet());
                        sortResultMap(mapEntryList);
                        int toIndex = numOfQueues > queueNames.size() ? queueNames.size() : numOfQueues;
                        toIndex = Math.min(mapEntryList.size(), toIndex);
                        callback.onDone(mapEntryList.subList(0, toIndex));
                    }
                });
            }
        } else {
            callback.onDone(mapEntryList);
        }
    }

    public void updateEnqueue() {
        vertx.eventBus().send(getMonitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + ENQUEUE_METRIC).put(METRIC_ACTION, MARK));
    }

    public void updateDequeue() {
        vertx.eventBus().send(getMonitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + DEQUEUE_METRIC).put(METRIC_ACTION, MARK));
    }

    public void updateListenerCount(long count){
        listenerCount.set(count);
    }

    public void updateRoutesCount(long count){
        routeCount.set(count);
    }

    private void sortResultMap(List<Map.Entry<String, Long>> input) {
        Ordering<Map.Entry<String, Long>> byMapValues = new Ordering<>() {
            @Override
            public int compare(Map.Entry<String, Long> left, Map.Entry<String, Long> right) {
                return left.getValue().compareTo(right.getValue());
            }
        };

        input.sort(byMapValues.reverse());
    }

    private boolean isRequestToExternalTarget(String target) {
        boolean isInternalRequest = false;
        if (target != null) {
            isInternalRequest = target.contains("localhost") || target.contains("127.0.0.1");
        }
        return !isInternalRequest;
    }

    private boolean shouldBeTracked(String uri) {
        return !uri.contains("/jmx/") && !uri.endsWith("cleanup");
    }
}

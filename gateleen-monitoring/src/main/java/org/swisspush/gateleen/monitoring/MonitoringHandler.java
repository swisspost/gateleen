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

    private Vertx vertx;
    private ResourceStorage storage;

    private boolean requestPerRuleMonitoringActive;
    private String requestPerRuleMonitoringProperty;
    private final String requestPerRuleMonitoringPath;
    private Map<String, Long> requestPerRuleMonitoringMap;

    private static Logger log = LoggerFactory.getLogger(MonitoringHandler.class);

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

    private static final int QUEUE_SIZE_REFRESH_TIME = 5000; // 5 seconds

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
        this.vertx = vertx;
        this.storage = storage;
        this.prefix = prefix;
        this.requestPerRuleMonitoringPath = initRequestPerRuleMonitoringPath(requestPerRulePath);
        this.uuid = UUID.randomUUID();

        registerQueueSizeTrackingTimer();

        initRequestPerRuleMonitoring();

        final Logger metricLogger = LoggerFactory.getLogger("Metrics");

        final Map<String, Long> metricCache = new HashMap<>();
        final Map<String, Long> lastDumps = new HashMap<>();

        vertx.eventBus().consumer(getMonitoringAddress(), (Handler<Message<JsonObject>>) message -> {
            final JsonObject body = message.body();
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
        });
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

    private Map<String, Long> getRequestPerRuleMonitoringMap() {
        if(requestPerRuleMonitoringMap == null){
            requestPerRuleMonitoringMap = new HashMap<>();
        }
        return requestPerRuleMonitoringMap;
    }

    private void registerQueueSizeTrackingTimer() {
        vertx.setPeriodic(QUEUE_SIZE_REFRESH_TIME, event -> updateQueueCountInformation());
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
            vertx.eventBus().publish(getMonitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + REQUESTS_INCOMING_NAME).put(METRIC_ACTION, MARK));
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
            vertx.eventBus().publish(getMonitoringAddress(),
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
                vertx.eventBus().publish(getMonitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + REQUESTS_BACKENDS_NAME).put(METRIC_ACTION, MARK));
            } else {
                vertx.eventBus().publish(getMonitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + REQUESTS_CLIENT_NAME).put(METRIC_ACTION, MARK));
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
                vertx.eventBus().publish(getMonitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + "routing." + metricName).put(METRIC_ACTION, MARK));
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
                vertx.eventBus().publish(getMonitoringAddress(),
                        new JsonObject().put(METRIC_NAME, prefix + "routing." + metricName + ".duration").put(METRIC_ACTION, "set").put("n", duration));
            }
            updatePendingRequestCount(false);
        }
    }

    private void updatePendingRequestCount(boolean incrementCount) {
        final String action = incrementCount ? "inc" : "dec";
        log.trace("Updating count for pending requests: {} remaining", action);
        vertx.eventBus().publish(getMonitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + PENDING_REQUESTS_METRIC).put(METRIC_ACTION, action));
    }

    /**
     * Update the count of active queues. Reads the count from redis and stores it to JMX.
     */
    public void updateQueueCountInformation() {
        vertx.eventBus().request(getRedisquesAddress(), buildGetQueuesCountOperation(), (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
            if (reply.succeeded() && OK.equals(reply.result().body().getString(STATUS))) {
                final long count = reply.result().body().getLong(VALUE);
                vertx.eventBus().publish(getMonitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + ACTIVE_QUEUE_COUNT_METRIC).put(METRIC_ACTION, SET).put("n", count));
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
        log.trace("About to update last used Queue size counter");
        vertx.eventBus().request(getRedisquesAddress(), buildGetQueueItemsCountOperation(queue), (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
            if (reply.succeeded() && OK.equals(reply.result().body().getString(STATUS))) {
                final long count = reply.result().body().getLong(VALUE);
                vertx.eventBus().publish(getMonitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + LAST_USED_QUEUE_SIZE_METRIC).put(METRIC_ACTION, "set").put("n", count));
            } else {
                log.error("Error gathering queue size for queue '{}'", queue);
            }
        });
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
        vertx.eventBus().publish(getMonitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + ENQUEUE_METRIC).put(METRIC_ACTION, MARK));
    }

    public void updateDequeue() {
        vertx.eventBus().publish(getMonitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + DEQUEUE_METRIC).put(METRIC_ACTION, MARK));
    }

    public void updateListenerCount(long count){
        vertx.eventBus().publish(getMonitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + LISTENER_COUNT_METRIC).put(METRIC_ACTION, SET).put("n",count));
    }

    public void updateRoutesCount(long count){
        vertx.eventBus().publish(getMonitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + ROUTE_COUNT_METRIC).put(METRIC_ACTION, SET).put("n",count));

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

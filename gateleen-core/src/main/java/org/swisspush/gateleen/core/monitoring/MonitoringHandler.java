package org.swisspush.gateleen.core.monitoring;

import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.HttpServerRequestUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import com.google.common.collect.Ordering;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.RedisClient;
import io.vertx.redis.op.RangeLimitOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handler to monitor the server state using the Metrics library. The recorded informations are accessible through JMX MBeans.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class MonitoringHandler {

    public static final String METRIC_NAME = "name";
    public static final String METRIC_ACTION = "action";
    public static final String MARK = "mark";
    private Vertx vertx;
    private RedisClient redisClient;

    private static Logger log = LoggerFactory.getLogger(MonitoringHandler.class);

    public static final String REQUESTS_CLIENT_NAME = "requests.localhost";
    public static final String REQUESTS_BACKENDS_NAME = "requests.backends";
    private static final String REQUESTS_INCOMING_NAME = "requests.incoming";

    public static final String PENDING_REQUESTS_METRIC = "requests.pending.count";
    public static final String ACTIVE_QUEUE_COUNT_METRIC = "queues.active.count";
    public static final String LAST_USED_QUEUE_SIZE_METRIC = "queues.last.size";

    public static final String ENQUEUE_METRIC = "queues.enqueue";
    public static final String DEQUEUE_METRIC = "queues.dequeue";

    public static final String QUEUES_KEY_PREFIX = "redisques:queues";
    public static final int MAX_AGE_MILLISECONDS = 120000; // 120 seconds
    private static final int QUEUE_SIZE_REFRESH_TIME = 5000; // 5 seconds

    private String prefix;

    public interface MonitoringCallback {

        void onDone(JsonObject result);

        void onFail(String errorMessage, int statusCode);
    }

    private interface QueueLengthCollectingCallback {

        void onDone(List<Map.Entry<String, Long>> mapEntries);
    }

    public MonitoringHandler(Vertx vertx, RedisClient redisClient, String prefix) {
        this.vertx = vertx;
        this.redisClient = redisClient;
        this.prefix = prefix;
        registerQueueSizeTrackingTimer();

        final Logger metricLogger = LoggerFactory.getLogger("Metrics");

        final Map<String, Long> metricCache = new HashMap<>();
        final Map<String, Long> lastDumps = new HashMap<>();

        vertx.eventBus().consumer(Address.monitoringAddress(), new Handler<Message<JsonObject>>() {
            public void handle(Message<JsonObject> message) {
                final JsonObject body = message.body();
                final String action = body.getString(METRIC_ACTION);
                final String name = body.getString(METRIC_NAME);
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
        });
    }

    private void registerQueueSizeTrackingTimer() {
        vertx.setPeriodic(QUEUE_SIZE_REFRESH_TIME, event -> updateQueueCountInformation());
    }

    public void updateIncomingRequests(HttpServerRequest request) {
        if (!HttpServerRequestUtil.isRemoteAddressLoopbackAddress(request) && shouldBeTracked(request.uri())) {
            vertx.eventBus().publish(Address.monitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + REQUESTS_INCOMING_NAME).put(METRIC_ACTION, MARK));
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
                vertx.eventBus().publish(Address.monitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + REQUESTS_BACKENDS_NAME).put(METRIC_ACTION, MARK));
            } else {
                vertx.eventBus().publish(Address.monitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + REQUESTS_CLIENT_NAME).put(METRIC_ACTION, MARK));
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
                vertx.eventBus().publish(Address.monitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + "routing." + metricName).put(METRIC_ACTION, MARK));
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
                vertx.eventBus().publish(Address.monitoringAddress(),
                        new JsonObject().put(METRIC_NAME, prefix + "routing." + metricName + ".duration").put(METRIC_ACTION, "update").put("n", duration));
            }
            updatePendingRequestCount(false);
        }
    }

    private void updatePendingRequestCount(boolean incrementCount) {
        final String action = incrementCount ? "inc" : "dec";
        log.trace("Updating count for pending requests: " + action + "rementing");
        vertx.eventBus().publish(Address.monitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + PENDING_REQUESTS_METRIC).put(METRIC_ACTION, action));
    }

    /**
     * Update the count of active queues. Reads the count from redis and stores it to JMX.
     */
    public void updateQueueCountInformation() {
        long timestamp = System.currentTimeMillis() - MAX_AGE_MILLISECONDS;
        redisClient.zcount(QUEUES_KEY_PREFIX, timestamp, Double.MAX_VALUE, reply -> {
            if(reply.failed()){
                log.error("Error gathering count of active queues");
            } else {
                final long count = reply.result();
                vertx.eventBus().publish(Address.monitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + ACTIVE_QUEUE_COUNT_METRIC).put(METRIC_ACTION, "set").put("n", count));
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
        String queueName = QUEUES_KEY_PREFIX + ":" + queue;
        redisClient.llen(queueName, reply -> {
            if(reply.failed()){
                log.error("Error gathering queue size for queue '" + queue + "'");
            } else {
                final long count = reply.result();
                vertx.eventBus().publish(Address.monitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + LAST_USED_QUEUE_SIZE_METRIC).put(METRIC_ACTION, "update").put("n", count));
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
        long timestamp = System.currentTimeMillis() - MAX_AGE_MILLISECONDS;
        redisClient.zrangebyscore(QUEUES_KEY_PREFIX, String.valueOf(timestamp), "+inf", RangeLimitOptions.NONE, reply -> {
            if(reply.failed()){
                String error = "Error gathering names of active queues";
                log.error(error);
                callback.onFail(error, StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
            } else {
                final List<String> queueNames = reply.result().getList();
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
            }
        });
    }

    private void collectQueueLengths(final List<String> queueNames, final int numOfQueues, final boolean showEmptyQueues, final QueueLengthCollectingCallback callback) {
        final SortedMap<String, Long> resultMap = new TreeMap<>();
        final List<Map.Entry<String, Long>> mapEntryList = new ArrayList<>();
        final AtomicInteger subCommandCount = new AtomicInteger(queueNames.size());
        if (!queueNames.isEmpty()) {
            for (final String name : queueNames) {
                final String queueName = QUEUES_KEY_PREFIX + ":" + name;
                redisClient.llen(queueName, reply -> {
                    subCommandCount.decrementAndGet();
                    if(reply.failed()){
                        log.error("Error gathering size of queue " + queueName);
                    } else {
                        final long count = reply.result();
                        if (showEmptyQueues || count > 0) {
                            resultMap.put(name, count);
                        }
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
        vertx.eventBus().publish(Address.monitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + ENQUEUE_METRIC).put(METRIC_ACTION, MARK));
    }

    public void updateDequeue() {
        vertx.eventBus().publish(Address.monitoringAddress(), new JsonObject().put(METRIC_NAME, prefix + DEQUEUE_METRIC).put(METRIC_ACTION, MARK));
    }

    private void sortResultMap(List<Map.Entry<String, Long>> input) {
        Ordering<Map.Entry<String, Long>> byMapValues = new Ordering<Map.Entry<String, Long>>() {
            @Override
            public int compare(Map.Entry<String, Long> left, Map.Entry<String, Long> right) {
                return left.getValue().compareTo(right.getValue());
            }
        };

        Collections.sort(input, byMapValues.reverse());
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

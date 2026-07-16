package org.swisspush.gateleen.queue.queuing;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.redis.RedisProvider;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.ExpiryCheckHandler;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.queue.duplicate.DuplicateCheckHandler;
import org.swisspush.gateleen.queue.queuing.splitter.NoOpQueueSplitter;
import org.swisspush.gateleen.queue.queuing.splitter.QueueSplitter;

import javax.annotation.Nullable;

import static org.swisspush.redisques.util.RedisquesAPI.buildCheckOperation;

/**
 * Queues requests.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class QueuingHandler implements Handler<Buffer> {
    private Logger log = LoggerFactory.getLogger(QueuingHandler.class);
    public static final String QUEUE_HEADER = "x-queue";
    public static final String ORIGINALLY_QUEUED_HEADER = "x-originally-queued";
    public static final String DUPLICATE_CHECK_HEADER = "x-duplicate-check";
    public static final String CLIENT_TIMESTAMP_HEADER = "X-Client-Timestamp";

    private final RequestQueue requestQueue;

    public static boolean isQueued(HttpServerRequest request) {
        String queue = request.headers().get(QUEUE_HEADER);
        return HttpMethod.GET != request.method() && queue != null && !queue.trim().isEmpty();
    }

    private final HttpServerRequest request;
    private final Vertx vertx;
    private final RedisProvider redisProvider;
    private final QueueSplitter queueSplitter;
    private final boolean enqueueExpiredRequest;

    public QueuingHandler(
            Vertx vertx,
            RedisProvider redisProvider,
            HttpServerRequest request,
            @Nullable MonitoringHandler monitoringHandler
    ) {
        this(vertx, redisProvider, request, new QueueClient(vertx, monitoringHandler), new NoOpQueueSplitter());
    }

    public QueuingHandler(
            Vertx vertx,
            RedisProvider redisProvider,
            HttpServerRequest request,
            @Nullable MonitoringHandler monitoringHandler,
            QueueSplitter queueSplitter
    ) {
        this(
                vertx,
                redisProvider,
                request,
                new QueueClient(vertx, monitoringHandler),
                queueSplitter == null ? new NoOpQueueSplitter() : queueSplitter
        );
    }

    public QueuingHandler(
            Vertx vertx,
            RedisProvider redisProvider,
            HttpServerRequest request,
            RequestQueue requestQueue,
            QueueSplitter queueSplitter
    ) {
        this(vertx, redisProvider, request, requestQueue, queueSplitter, true);
    }

    public QueuingHandler(
            Vertx vertx,
            RedisProvider redisProvider,
            HttpServerRequest request,
            RequestQueue requestQueue,
            QueueSplitter queueSplitter,
            boolean enqueueExpiredRequest
    ) {
        this.request = request;
        this.vertx = vertx;
        this.redisProvider = redisProvider;
        this.requestQueue = requestQueue;
        this.queueSplitter = queueSplitter;
        this.enqueueExpiredRequest = enqueueExpiredRequest;
    }

    @Override
    public void handle(final Buffer buffer) {
        final String queue = request.headers().get(QUEUE_HEADER);
        final MultiMap headers = request.headers();

        if (!enqueueExpiredRequest && isRequestExpired(headers)) {
            // just skip this request, because the queue already expired
            log.info("Dropping expired queued request '{}'", request.uri());
            request.response().setStatusCode(StatusCode.ACCEPTED.getStatusCode());
            request.response().setStatusMessage(StatusCode.ACCEPTED.getStatusMessage());
            request.response().end();
            return;
        }

        // Remove the queue header to avoid feedback loop
        headers.remove(QUEUE_HEADER);

        // Add a header to indicate that this request was initially queued
        headers.add(ORIGINALLY_QUEUED_HEADER, "true");

        if (headers.names().contains(DUPLICATE_CHECK_HEADER)) {
            DuplicateCheckHandler.checkDuplicateRequest(redisProvider, request.uri(), buffer, headers.get(DUPLICATE_CHECK_HEADER), requestIsDuplicate -> {
                if (requestIsDuplicate) {
                    // don't handle this request since it's a duplicate
                    request.response().setStatusCode(StatusCode.ACCEPTED.getStatusCode());
                    request.response().setStatusMessage(StatusCode.ACCEPTED.getStatusMessage());
                    request.response().end();
                } else {
                    requestQueue.enqueue(request, headers, buffer, queueSplitter.convertToSubQueue(queue, request));
                }
            });

        } else {
            requestQueue.enqueue(request, headers, buffer, queueSplitter.convertToSubQueue(queue, request));
        }
    }

    /**
     * Checks whether the request has expired based on the "X-Client-Timestamp" header
     * (the moment the client originally sent the request) together with the
     * "x-queue-expire-after" (or "X-Expire-After") header defining how long the request
     * is valid for.
     *
     * @param headers the request headers
     * @return true if the request has expired, false otherwise (also if no client
     * timestamp is present or its value can't be parsed)
     */
    public static boolean isRequestExpired(MultiMap headers) {
        if (headers == null || headers.isEmpty()) {
            return false;
        }
        String clientTimestamp = headers.get(CLIENT_TIMESTAMP_HEADER);
        if (clientTimestamp == null) {
            return false;
        }
        try {
            long timestamp = ExpiryCheckHandler.parseDateTime(clientTimestamp).getMillis();
            return ExpiryCheckHandler.isExpired(headers, timestamp);
        } catch (IllegalArgumentException e) {
            // invalid/unparseable timestamp, treat request as not expired
            return false;
        }
    }

    /**
     * @param vertx the vertx instance
     * @deprecated Use vertx-redisques version 2.2.1 or higher, since redisques makes the cleanup automatically
     */
    @Deprecated
    public static void cleanup(Vertx vertx) {
        vertx.eventBus().request(Address.redisquesAddress(), buildCheckOperation());
    }
}

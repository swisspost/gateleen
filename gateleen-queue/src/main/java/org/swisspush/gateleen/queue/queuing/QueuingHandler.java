package org.swisspush.gateleen.queue.queuing;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import org.swisspush.gateleen.core.redis.RedisProvider;
import org.swisspush.gateleen.core.util.Address;
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

    public static final String QUEUE_HEADER = "x-queue";
    public static final String ORIGINALLY_QUEUED_HEADER = "x-originally-queued";
    public static final String DUPLICATE_CHECK_HEADER = "x-duplicate-check";

    private final RequestQueue requestQueue;

    public static boolean isQueued(HttpServerRequest request) {
        String queue = request.headers().get(QUEUE_HEADER);
        return HttpMethod.GET != request.method() && queue != null && !queue.trim().isEmpty();
    }

    private final HttpServerRequest request;
    private final Vertx vertx;
    private final RedisProvider redisProvider;
    private final QueueSplitter queueSplitter;

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
        this.request = request;
        this.vertx = vertx;
        this.redisProvider = redisProvider;
        this.requestQueue = requestQueue;
        this.queueSplitter = queueSplitter;
    }

    @Override
    public void handle(final Buffer buffer) {
        final String queue = request.headers().get(QUEUE_HEADER);
        final MultiMap headers = request.headers();
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
     * @param vertx the vertx instance
     * @deprecated Use vertx-redisques version 2.2.1 or higher, since redisques makes the cleanup automatically
     */
    @Deprecated
    public static void cleanup(Vertx vertx) {
        vertx.eventBus().request(Address.redisquesAddress(), buildCheckOperation());
    }
}

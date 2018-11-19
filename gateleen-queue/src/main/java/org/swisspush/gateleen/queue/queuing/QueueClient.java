package org.swisspush.gateleen.queue.queuing;

import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.monitoring.MonitoringHandler;

import static org.swisspush.redisques.util.RedisquesAPI.*;

/**
 * The QueueClient allows you to enqueue various requests.
 *
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class QueueClient implements RequestQueue {
    public static final String QUEUE_TIMESTAMP = "queueTimestamp";
    public static final Logger log = LoggerFactory.getLogger(QueueClient.class);
    private MonitoringHandler monitoringHandler;
    private Vertx vertx;

    /**
     * Creates a new instance of the QueueClient.
     *
     * @param vertx             vertx
     * @param monitoringHandler monitoringHandler
     */
    public QueueClient(Vertx vertx, MonitoringHandler monitoringHandler) {
        this.vertx = vertx;
        this.monitoringHandler = monitoringHandler;
    }

    /**
     * Get the event bus address of redisques.
     * Override this method when you want to use a custom redisques address
     *
     * @return the event bus address of redisques
     */
    protected String getRedisquesAddress() {
        return Address.redisquesAddress();
    }

    /**
     * Enqueues the given request.
     *
     * @param request request
     * @param buffer  buffer
     * @param queue   queue
     */
    @Override
    public void enqueue(final HttpServerRequest request, Buffer buffer, final String queue) {
        enqueue(request, request.headers(), buffer, queue);
    }

    /**
     * Enqueues the given request.
     *
     * @param request request
     * @param headers headers
     * @param buffer  buffer
     * @param queue   queue
     */
    @Override
    public void enqueue(final HttpServerRequest request, MultiMap headers, Buffer buffer, final String queue) {
        HttpRequest queuedRequest = new HttpRequest(request.method(), request.uri(), headers, buffer.getBytes());
        enqueue(request, queuedRequest, queue);
    }

    /**
     * Enqueues a disconnected request.
     *
     * @param request - selfmade request
     * @param queue   queue
     */
    @Override
    public void enqueue(HttpRequest request, final String queue) {
        enqueue(null, request, queue);
    }

    /**
     * Enqueues a disconnected request.
     *
     * @param request     - selfmade request
     * @param queue       queue
     * @param doneHandler a handler which is called as soon as the request is written into the queue.
     */
    @Override
    public void enqueue(HttpRequest request, final String queue, final Handler<Void> doneHandler) {
        enqueue(null, request, queue, doneHandler);
    }

    /**
     * Enqueues a request into a locked queue.
     *
     * @param queuedRequest   the request to enqueue
     * @param queue           queue
     * @param lockRequestedBy the user requesting the lock
     * @param doneHandler     a handler which is called as soon as the request is written into the queue.
     */
    @Override
    public void lockedEnqueue(HttpRequest queuedRequest, String queue, String lockRequestedBy, Handler<Void> doneHandler) {
        vertx.eventBus().send(getRedisquesAddress(), buildLockedEnqueueOperation(queue,
                queuedRequest.toJsonObject().put(QUEUE_TIMESTAMP, System.currentTimeMillis()).encode(), lockRequestedBy),
                (Handler<AsyncResult<Message<JsonObject>>>) event -> {
                    if (OK.equals(event.result().body().getString(STATUS))) {
                        monitoringHandler.updateLastUsedQueueSizeInformation(queue);
                        monitoringHandler.updateEnqueue();
                    }
                    // call the done handler to tell,
                    // that the request was written
                    if (doneHandler != null) {
                        doneHandler.handle(null);
                    }
                });
    }

    /**
     * Deletes the lock for the provided queue
     *
     * @param queue the queue to unlock
     * @return a future which is completed when reply status from redisques was 'OK', fails otherwise
     */
    @Override
    public Future<Void> deleteLock(String queue) {
        Future<Void> future = Future.future();
        vertx.eventBus().send(getRedisquesAddress(), buildDeleteLockOperation(queue), (Handler<AsyncResult<Message<JsonObject>>>) event -> {
            if (event.failed()) {
                future.fail(event.cause());
                return;
            }
            if (OK.equals(event.result().body().getString(STATUS))) {
                future.complete();
                return;
            }
            future.fail("Failed to delete lock for queue " + queue);
        });
        return future;
    }


    /**
     * Deletes all queue items of the provided queue and eventually deletes the lock too.
     *
     * @param queue  the queue to delete
     * @param unlock delete the lock after the queue has been deleted
     * @return a future which is completed when reply from redisques succeeded, fails otherwise
     */
    @Override
    public Future<Void> deleteAllQueueItems(String queue, boolean unlock) {
        Future<Void> future = Future.future();
        vertx.eventBus().send(getRedisquesAddress(), buildDeleteAllQueueItemsOperation(queue, unlock), (Handler<AsyncResult<Message<JsonObject>>>) event -> {
            if (event.succeeded()) {
                future.complete();
            } else {
                future.fail("Failed to delete all queue items for queue " + queue + " with unlock " + unlock + ". Cause: " + event.cause());
            }
        });
        return future;
    }

    @Override
    public Future<Void> enqueueFuture(HttpRequest queuedRequest, String queue) {
        Future<Void> future = Future.future();
        vertx.eventBus().send(getRedisquesAddress(), buildEnqueueOperation(queue,
                queuedRequest.toJsonObject().put(QUEUE_TIMESTAMP, System.currentTimeMillis()).encode()),
                (Handler<AsyncResult<Message<JsonObject>>>) event -> {
                    if (OK.equals(event.result().body().getString(STATUS))) {
                        monitoringHandler.updateLastUsedQueueSizeInformation(queue);
                        monitoringHandler.updateEnqueue();
                        future.complete();
                    } else {
                        future.fail(event.result().body().getString(MESSAGE));
                    }
                });
        return future;
    }

    /**
     * Enques a request. <br />
     * If no X-Server-Timestamp and / or X-Expire-After headers
     * are set, the server sets the actual timestamp and a default
     * expiry time of 2 seconds.
     *
     * @param request       - a client made request, therefor connected, can take responses. The request can be null, if only a selfmade request is available.
     * @param queuedRequest - a selfmade request, not connected to a client, can't take responses!
     * @param queue         queue
     */
    private void enqueue(final HttpServerRequest request, HttpRequest queuedRequest, final String queue) {
        enqueue(request, queuedRequest, queue, null);
    }

    /**
     * Enques a request. <br />
     * If no X-Server-Timestamp and / or X-Expire-After headers
     * are set, the server sets the actual timestamp and a default
     * expiry time of 2 seconds.
     *
     * @param request       - a client made request, therefor connected, can take responses. The request can be null, if only a selfmade request is available.
     * @param queuedRequest - a selfmade request, not connected to a client, can't take responses!
     * @param queue         queue
     * @param doneHandler   a handler which is called as soon as the request is written into the queue.
     */
    private void enqueue(final HttpServerRequest request, HttpRequest queuedRequest, final String queue, final Handler<Void> doneHandler) {
        if( !QueueProcessor.httpMethodIsQueueable(queuedRequest.getMethod()) ){
            log.warn( "Ignore enqueue of unsupported HTTP method in '{} {}'.", queuedRequest.getMethod() , queuedRequest.getUri());
            if( doneHandler != null ) doneHandler.handle(null);
            return;
        }
        vertx.eventBus().send(getRedisquesAddress(), buildEnqueueOperation(queue, queuedRequest.toJsonObject().put(QUEUE_TIMESTAMP, System.currentTimeMillis()).encode()),
                (Handler<AsyncResult<Message<JsonObject>>>) event -> {
                    if (OK.equals(event.result().body().getString(STATUS))) {
                        monitoringHandler.updateLastUsedQueueSizeInformation(queue);
                        monitoringHandler.updateEnqueue();

                        if (request != null) {
                            ResponseStatusCodeLogUtil.info(request, StatusCode.ACCEPTED, QueueClient.class);
                            request.response().setStatusCode(StatusCode.ACCEPTED.getStatusCode());
                            request.response().setStatusMessage(StatusCode.ACCEPTED.getStatusMessage());
                            request.response().end();
                        }
                    } else if (request != null) {
                        ResponseStatusCodeLogUtil.info(request, StatusCode.INTERNAL_SERVER_ERROR, QueueClient.class);
                        request.response().setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
                        request.response().setStatusMessage(StatusCode.INTERNAL_SERVER_ERROR.getStatusMessage());
                        request.response().end(event.result().body().getString(MESSAGE));
                    }

                    // call the done handler to tell,
                    // that the request was written
                    if (doneHandler != null) {
                        doneHandler.handle(null);
                    }
                });
    }
}
